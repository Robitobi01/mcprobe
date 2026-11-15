// mcprobe-web: live view of the files mcprobe writes for the instances of one host.
//
// GET /                    instance list (JSON)
// GET /<instance>/         viewer (static, embedded)
// GET /<instance>/ticks    ticks.json as written by the agent
// GET /<instance>/chunks   chunks.json (loaded and lazy chunks per dimension)
// GET /<instance>/stream?window=SECONDS
//
//	Server-sent events, one per second: {"profile": <as /profile>, "ticks": <ticks.json>}.
//	Profiling runs while at least one stream is open.
//
// GET /<instance>/watch[?ticks][&chunks]
//
//	Server-sent events for long-running subscribers: "event: ticks" / "event: chunks" with the
//	file's JSON whenever the agent rewrote it (default: both). Does not start the profiler.
//
// GET /<instance>/profile?window=SECONDS
//
//	Keeps the agent's profiler running (touches profile.request) and returns the server
//	thread's call tree for the last SECONDS, with obfuscated 1.12 names translated through
//	MCP mappings. The agent writes cumulative counts; windows are differences between
//	snapshots kept here.
//
// Nothing else from the instance directories (e.g. key.json) is ever served.
package main

import (
	"bufio"
	"bytes"
	"embed"
	"encoding/csv"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

//go:embed static
var static embed.FS

const keepHistory = 10 * time.Minute

type snapshot struct {
	at     time.Time
	counts map[string]int64
}

type instance struct {
	dir     string
	mu      sync.Mutex
	start   int64 // profile start (ms) of the snapshots in hist
	mtime   time.Time
	hist    []snapshot
	touched time.Time
}

type node struct {
	Name     string  `json:"n"`
	Value    int64   `json:"v"`
	Self     int64   `json:"s,omitempty"`
	Children []*node `json:"c,omitempty"`
	index    map[string]*node
}

func (n *node) child(name string) *node {
	if n.index == nil {
		n.index = map[string]*node{}
	}
	c := n.index[name]
	if c == nil {
		c = &node{Name: name}
		n.index[name] = c
		n.Children = append(n.Children, c)
	}
	return c
}

func (n *node) sort() {
	sort.Slice(n.Children, func(i, j int) bool { return n.Children[i].Value > n.Children[j].Value })
	for _, c := range n.Children {
		c.sort()
	}
}

// --- MCP name translation ---------------------------------------------------------------

type mappings struct {
	classes map[string]string // obf -> deobf (with package)
	methods map[string]string // "obfClass/name desc" -> "DeobfClass.mcpName"
	mu      sync.Mutex
	cache   map[string]string
}

func short(class string) string { return class[strings.LastIndex(class, "/")+1:] }

func loadMappings(dir string) *mappings {
	m := &mappings{classes: map[string]string{}, methods: map[string]string{}, cache: map[string]string{}}
	if dir == "" {
		return m
	}
	names := map[string]string{}
	if f, err := os.Open(filepath.Join(dir, "methods.csv")); err == nil {
		rows, _ := csv.NewReader(f).ReadAll()
		for _, r := range rows {
			if len(r) >= 2 {
				names[r[0]] = r[1]
			}
		}
		f.Close()
	} else {
		log.Printf("mappings: %v", err)
	}
	f, err := os.Open(filepath.Join(dir, "joined.srg"))
	if err != nil {
		log.Printf("mappings: %v (names stay obfuscated)", err)
		return m
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		p := strings.Fields(sc.Text())
		switch {
		case len(p) == 3 && p[0] == "CL:":
			m.classes[p[1]] = p[2]
		case len(p) == 5 && p[0] == "MD:":
			// MD: obfClass/obfName obfDesc deobfClass/srgName deobfDesc
			i := strings.LastIndex(p[3], "/")
			name := p[3][i+1:]
			if n, ok := names[name]; ok {
				name = n
			}
			m.methods[p[1]+" "+p[2]] = short(p[3][:i]) + "." + name
		}
	}
	log.Printf("mappings: %d classes, %d methods", len(m.classes), len(m.methods))
	return m
}

// translate turns "oo.d()V" / "net/minecraft/server/MinecraftServer.C()V" into
// "WorldServer.tick"; other Java frames lose their signature; native frames stay as they are.
func (m *mappings) translate(frame string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	if t, ok := m.cache[frame]; ok {
		return t
	}
	t := frame
	if open := strings.IndexByte(frame, '('); open > 0 {
		dot := strings.LastIndexByte(frame[:open], '.')
		if dot > 0 {
			class, method := frame[:dot], frame[dot+1:open]
			desc := strings.ReplaceAll(frame[open:], "|", ";")
			if mapped, ok := m.methods[class+"/"+method+" "+desc]; ok {
				t = mapped
			} else if deobf, ok := m.classes[class]; ok {
				t = short(deobf) + "." + method
			} else {
				t = short(class) + "." + method
			}
		}
	}
	m.cache[frame] = t
	return t
}

// --- profile data -----------------------------------------------------------------------

// read parses profile.collapsed: "# start=<ms>" and "frame;frame;... count" lines.
func readProfile(path string) (int64, map[string]int64, time.Time, error) {
	st, err := os.Stat(path)
	if err != nil {
		return 0, nil, time.Time{}, err
	}
	f, err := os.Open(path)
	if err != nil {
		return 0, nil, time.Time{}, err
	}
	defer f.Close()
	var start int64
	counts := map[string]int64{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 16<<20)
	for sc.Scan() {
		line := sc.Text()
		if strings.HasPrefix(line, "# start=") {
			start, _ = strconv.ParseInt(line[8:], 10, 64)
			continue
		}
		sp := strings.LastIndexByte(line, ' ')
		if sp <= 0 {
			continue
		}
		if n, err := strconv.ParseInt(line[sp+1:], 10, 64); err == nil {
			counts[line[:sp]] += n
		}
	}
	return start, counts, st.ModTime(), sc.Err()
}

func (in *instance) touch() {
	if time.Since(in.touched) < 5*time.Second {
		return
	}
	p := filepath.Join(in.dir, "profile.request")
	now := time.Now()
	if err := os.Chtimes(p, now, now); err != nil {
		if f, err := os.OpenFile(p, os.O_CREATE|os.O_WRONLY, 0o640); err == nil {
			f.Close()
		} else {
			log.Printf("%s: %v", p, err)
			return
		}
	}
	in.touched = now
}

// window returns the counts of the last `window` and the time actually covered.
func (in *instance) window(window time.Duration) (map[string]int64, time.Duration, bool) {
	start, counts, mtime, err := readProfile(filepath.Join(in.dir, "profile.collapsed"))
	if err != nil {
		return nil, 0, false
	}
	if start != in.start { // profiler (re)started: old snapshots are from another run
		in.start, in.hist = start, nil
	}
	if !mtime.Equal(in.mtime) {
		in.mtime = mtime
		in.hist = append(in.hist, snapshot{mtime, counts})
	}
	for len(in.hist) > 1 && time.Since(in.hist[0].at) > keepHistory {
		in.hist = in.hist[1:]
	}
	latest := in.hist[len(in.hist)-1]
	// base: newest snapshot at least `window` older than the latest; else the profile start
	var base map[string]int64
	covered := latest.at.Sub(time.UnixMilli(start))
	for i := len(in.hist) - 1; i >= 0; i-- {
		if latest.at.Sub(in.hist[i].at) >= window {
			base, covered = in.hist[i].counts, latest.at.Sub(in.hist[i].at)
			break
		}
	}
	delta := map[string]int64{}
	for stack, n := range latest.counts {
		if d := n - base[stack]; d > 0 {
			delta[stack] = d
		}
	}
	return delta, covered, true
}

// buildTree keeps the stacks of one thread; the agent prefixes each with "[<thread> tid=N]".
func buildTree(counts map[string]int64, m *mappings, thread string) *node {
	root := &node{Name: thread}
	prefix := "[" + thread + " tid="
	for stack, n := range counts {
		first, rest, ok := strings.Cut(stack, ";")
		if !ok || !strings.HasPrefix(first, prefix) {
			continue
		}
		cur := root
		cur.Value += n
		for _, frame := range strings.Split(rest, ";") {
			cur = cur.child(m.translate(frame))
			cur.Value += n
		}
		cur.Self += n
	}
	root.sort()
	return root
}

// --- http -------------------------------------------------------------------------------

type instanceFlags map[string]*instance

func (f instanceFlags) String() string { return "" }
func (f instanceFlags) Set(v string) error {
	name, dir, ok := strings.Cut(v, "=")
	if !ok || name == "" || strings.ContainsAny(name, "/.") {
		return &os.PathError{Op: "instance", Path: v, Err: os.ErrInvalid}
	}
	f[name] = &instance{dir: dir}
	return nil
}

// defaultMappings: the mappings shipped next to the binary (<pkg>/share/mcprobe/mappings).
func defaultMappings() string {
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	return filepath.Join(filepath.Dir(filepath.Dir(exe)), "share", "mcprobe", "mappings")
}

// watch streams each named file (<name>.json) whenever its modification time changes.
func watch(w http.ResponseWriter, r *http.Request, dir string, names []string) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	seen := map[string]time.Time{}
	poll := time.NewTicker(250 * time.Millisecond)
	defer poll.Stop()
	lastWrite := time.Now()
	for {
		for _, n := range names {
			path := filepath.Join(dir, n+".json")
			st, err := os.Stat(path)
			if err != nil || st.ModTime().Equal(seen[n]) {
				continue
			}
			data, err := os.ReadFile(path)
			if err != nil || !json.Valid(data) {
				continue
			}
			seen[n] = st.ModTime()
			if _, err := fmt.Fprintf(w, "event: %s\ndata: %s\n\n", n, compact(data)); err != nil {
				return
			}
			lastWrite = time.Now()
		}
		if time.Since(lastWrite) > 15*time.Second { // keep proxies from closing idle streams
			if _, err := fmt.Fprint(w, ": keepalive\n\n"); err != nil {
				return
			}
			lastWrite = time.Now()
		}
		flusher.Flush()
		select {
		case <-r.Context().Done():
			return
		case <-poll.C:
		}
	}
}

// compact removes newlines (one SSE data line per event).
func compact(data []byte) []byte {
	var b bytes.Buffer
	if json.Compact(&b, data) != nil {
		return data
	}
	return b.Bytes()
}

func windowParam(r *http.Request) time.Duration {
	secs, _ := strconv.Atoi(r.URL.Query().Get("window"))
	if secs <= 0 || secs > int(keepHistory/time.Second) {
		secs = 30
	}
	return time.Duration(secs) * time.Second
}

// profile keeps the agent profiling and returns the server thread's tree for the window.
func (in *instance) profile(window time.Duration, m *mappings) map[string]any {
	in.mu.Lock()
	defer in.mu.Unlock()
	in.touch()
	counts, covered, ok := in.window(window)
	if !ok {
		return map[string]any{"status": "starting"}
	}
	return map[string]any{
		"status":   "running",
		"start":    in.start,
		"interval": 2, // ms of CPU time per sample (agent: itimer, 2ms)
		"covered":  covered.Seconds(),
		"tree":     buildTree(counts, m, "Server thread"),
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(v)
}

func main() {
	listen := flag.String("listen", "127.0.0.1:23000", "address to listen on")
	mappingDir := flag.String("mappings", defaultMappings(), "MCP mappings directory (joined.srg, methods.csv)")
	instances := instanceFlags{}
	flag.Var(instances, "instance", "name=dir of an instance's mcprobe output (repeatable)")
	flag.Parse()
	m := loadMappings(*mappingDir)
	assets, _ := fs.Sub(static, "static")
	files := http.FileServer(http.FS(assets))

	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 2)
		if parts[0] == "" {
			names := make([]string, 0, len(instances))
			for n := range instances {
				names = append(names, n)
			}
			sort.Strings(names)
			writeJSON(w, names)
			return
		}
		in := instances[parts[0]]
		if in == nil {
			http.NotFound(w, r)
			return
		}
		if len(parts) == 1 {
			http.Redirect(w, r, r.URL.Path+"/", http.StatusMovedPermanently)
			return
		}
		switch parts[1] {
		case "ticks", "chunks":
			data, err := os.ReadFile(filepath.Join(in.dir, parts[1]+".json"))
			if err != nil {
				http.Error(w, "server not running", http.StatusServiceUnavailable)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Cache-Control", "no-store")
			w.Write(data)
		case "profile":
			writeJSON(w, in.profile(windowParam(r), m))
		case "watch":
			q := r.URL.Query()
			names := []string{}
			for _, n := range []string{"ticks", "chunks"} {
				if q.Has(n) {
					names = append(names, n)
				}
			}
			if len(names) == 0 {
				names = []string{"ticks", "chunks"}
			}
			watch(w, r, in.dir, names)
		case "stream":
			flusher, ok := w.(http.Flusher)
			if !ok {
				http.Error(w, "streaming unsupported", http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "text/event-stream")
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("X-Accel-Buffering", "no") // nginx: pass events through immediately
			window := windowParam(r)
			tick := time.NewTicker(time.Second)
			defer tick.Stop()
			for {
				ticks, err := os.ReadFile(filepath.Join(in.dir, "ticks.json"))
				if err != nil || !json.Valid(ticks) {
					ticks = []byte("null")
				}
				event, _ := json.Marshal(map[string]any{"profile": in.profile(window, m), "ticks": json.RawMessage(ticks)})
				if _, err := fmt.Fprintf(w, "data: %s\n\n", event); err != nil {
					return
				}
				flusher.Flush()
				select {
				case <-r.Context().Done():
					return
				case <-tick.C:
				}
			}
		default:
			r2 := r.Clone(r.Context())
			r2.URL.Path = "/" + parts[1]
			files.ServeHTTP(w, r2)
		}
	})
	log.Printf("listening on %s (%d instances)", *listen, len(instances))
	log.Fatal(http.ListenAndServe(*listen, nil))
}
