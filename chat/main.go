// mcprobe-chat: in-game chat features for MCSManager instances.
// Reads chat from each instance's logs/latest.log and tick times from mcprobe's ticks.json,
// answers through the MCSManager panel API (commands land in the server console queue and run
// on the main thread). Modules per instance: calc, links, tps, mspt (actionbar), twitch.
package main

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

type Instance struct {
	UUID   string `json:"uuid"`
	Log    string `json:"log"`   // logs/latest.log
	Probe  string `json:"probe"` // mcprobe output dir ("" = no tick data)
	Calc   bool   `json:"calc"`
	Links  bool   `json:"links"`
	TPS    bool   `json:"tps"`
	MSPT   bool   `json:"mspt"`
	Twitch bool   `json:"twitch"`

	name string
	cmds chan string
}

type Config struct {
	Daemon    string               `json:"daemon"`
	Twitch    []string             `json:"twitch"` // channels relayed while "!twitch on"
	Instances map[string]*Instance `json:"instances"`
}

var (
	api, apiKey string
	cfg         Config
	client      = &http.Client{Timeout: 10 * time.Second}
	chatRx      = regexp.MustCompile(`^\[[\d:]+\] \[Server thread/INFO\]: <([^>]+)> (.*)$`)
	urlRx       = regexp.MustCompile(`(?i)https?://[^\s<>"']+`)
)

func main() {
	path := flag.String("config", "", "JSON config")
	flag.Parse()
	api, apiKey = os.Getenv("API"), os.Getenv("APIKEY")
	b, err := os.ReadFile(*path)
	if err != nil {
		log.Fatal(err)
	}
	if err := json.Unmarshal(b, &cfg); err != nil {
		log.Fatal(err)
	}
	if api == "" || apiKey == "" {
		log.Fatal("API and APIKEY must be set")
	}
	for name, in := range cfg.Instances {
		in.name, in.cmds = name, make(chan string, 64)
		go in.sender()
		go in.tail()
		if in.MSPT && in.Probe != "" {
			go in.msptLoop()
		}
	}
	select {}
}

// --- commands ---

func (in *Instance) send(cmd string) {
	select {
	case in.cmds <- cmd:
	default: // server not answering; drop instead of piling up
	}
}

func (in *Instance) sender() {
	for cmd := range in.cmds {
		q := url.Values{"apikey": {apiKey}, "daemonId": {cfg.Daemon}, "uuid": {in.UUID}, "command": {cmd}}
		resp, err := client.Get(api + "?" + q.Encode())
		if err != nil {
			continue
		}
		io.Copy(io.Discard, resp.Body)
		resp.Body.Close()
	}
}

func (in *Instance) tellraw(v any) { in.send("tellraw @a " + jsonText(v)) }

func jsonText(v any) string {
	var b strings.Builder
	e := json.NewEncoder(&b)
	e.SetEscapeHTML(false)
	e.Encode(v)
	return strings.TrimSpace(b.String())
}

type text = map[string]any

func gray(s string) text { return text{"text": s, "color": "gray"} }

// --- chat ---

// tail follows the log: starts at its end, reopens it when the server starts a new one.
func (in *Instance) tail() {
	var f *os.File
	var ino uint64
	var pos int64
	var buf []byte
	first := true
	for ; ; time.Sleep(250 * time.Millisecond) {
		var st syscall.Stat_t
		if syscall.Stat(in.Log, &st) != nil {
			continue
		}
		if f == nil || st.Ino != ino || st.Size < pos {
			if f != nil {
				f.Close()
			}
			var err error
			if f, err = os.Open(in.Log); err != nil {
				f = nil
				continue
			}
			ino, pos, buf = st.Ino, 0, nil
			if first {
				pos = st.Size
			}
			first = false
		}
		if st.Size == pos {
			continue
		}
		chunk := make([]byte, st.Size-pos)
		n, _ := f.ReadAt(chunk, pos)
		pos += int64(n)
		buf = append(buf, chunk[:n]...)
		for {
			i := bytes.IndexByte(buf, '\n')
			if i < 0 {
				break
			}
			line := strings.TrimRight(string(buf[:i]), "\r")
			buf = buf[i+1:]
			if m := chatRx.FindStringSubmatch(line); m != nil {
				in.chat(m[1], m[2])
			}
		}
	}
}

func (in *Instance) chat(user, msg string) {
	if in.Calc && strings.HasPrefix(msg, "=") && len(msg) > 1 {
		if v, err := evalExpr(msg[1:]); err == nil {
			s := formatNum(v)
			in.tellraw(text{"text": s, "color": "gray", "clickEvent": text{"action": "suggest_command", "value": "=" + s}})
		} else if looksLikeMath(msg[1:]) {
			in.tellraw(text{"text": err.Error(), "color": "red"})
		}
	}
	if in.Links {
		for _, u := range urlRx.FindAllString(msg, 5) {
			in.tellraw(text{"text": u, "color": "gray", "clickEvent": text{"action": "open_url", "value": u}})
		}
	}
	switch strings.ToLower(strings.TrimSpace(msg)) {
	case "!tps":
		if in.TPS {
			if ms, ok := in.avgMSPT(100); ok {
				in.tellraw(gray(fmt.Sprintf("TPS: %.1f  MSPT: %.1f", min(20, 1000/ms), ms)))
			} else {
				in.tellraw(gray("TPS: n/a"))
			}
		}
	case "!twitch on":
		if in.Twitch {
			twitchSet(true)
		}
	case "!twitch off":
		if in.Twitch {
			twitchSet(false)
		}
	}
}

// --- tick data (mcprobe ticks.json) ---

type ticks struct {
	Time      int64   `json:"time"` // ms epoch of the last write
	TickNanos []int64 `json:"tickNanos"`
}

// avgMSPT averages the last n finished ticks; false if the server is not running.
func (in *Instance) avgMSPT(n int) (float64, bool) {
	b, err := os.ReadFile(in.Probe + "/ticks.json")
	if err != nil {
		return 0, false
	}
	var t ticks
	if json.Unmarshal(b, &t) != nil || len(t.TickNanos) == 0 || time.Since(time.UnixMilli(t.Time)) > 5*time.Second {
		return 0, false
	}
	l := t.TickNanos[max(0, len(t.TickNanos)-n):]
	var sum int64
	for _, v := range l {
		sum += v
	}
	return float64(sum) / float64(len(l)) / 1e6, true
}

// msptLoop shows the MSPT in the actionbar of players who ran /trigger mspt_trigger
// (needs `scoreboard objectives add mspt_trigger trigger` once).
func (in *Instance) msptLoop() {
	for i := 0; ; i++ {
		time.Sleep(time.Second)
		ms, ok := in.avgMSPT(20)
		if !ok {
			continue
		}
		color := "green"
		if ms > 50 {
			color = "dark_red"
		}
		in.send("title @a[score_mspt_trigger_min=1] actionbar " + jsonText(text{"text": fmt.Sprintf("%.0f", ms), "color": color}))
		if i%10 == 0 {
			in.send("scoreboard players enable @a mspt_trigger")
		}
	}
}

// --- twitch (anonymous read-only IRC, no account needed) ---

var (
	twitchOn   atomic.Bool
	twitchRun  atomic.Bool
	twitchMu   sync.Mutex
	twitchConn io.Closer
)

func broadcastTwitch(v any) {
	for _, in := range cfg.Instances {
		if in.Twitch {
			in.tellraw(v)
		}
	}
}

func twitchSet(on bool) {
	twitchMu.Lock()
	defer twitchMu.Unlock()
	if twitchOn.Swap(on) == on {
		return
	}
	state, color := "OFF", "red"
	if on {
		state, color = "ON", "green"
		go twitchLoop()
	} else if twitchConn != nil {
		twitchConn.Close()
	}
	broadcastTwitch([]any{"", text{"text": "Twitch Chat: ", "color": "dark_purple"}, text{"text": state, "bold": true, "color": color}})
}

func twitchLoop() {
	if !twitchRun.CompareAndSwap(false, true) {
		return // previous loop still running; it sees twitchOn again
	}
	defer twitchRun.Store(false)
	for twitchOn.Load() {
		if err := twitchSession(); err != nil && twitchOn.Load() {
			log.Print("twitch: ", err)
		}
		time.Sleep(5 * time.Second)
	}
}

func twitchSession() error {
	c, err := tls.Dial("tcp", "irc.chat.twitch.tv:6697", nil)
	if err != nil {
		return err
	}
	twitchMu.Lock()
	twitchConn = c
	on := twitchOn.Load()
	twitchMu.Unlock()
	defer c.Close()
	if !on {
		return nil
	}
	fmt.Fprintf(c, "NICK justinfan%d\r\n", 10000+rand.Intn(80000))
	for _, ch := range cfg.Twitch {
		fmt.Fprintf(c, "JOIN #%s\r\n", strings.ToLower(ch))
	}
	r := bufio.NewReader(c)
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return err
		}
		line = strings.TrimRight(line, "\r\n")
		if strings.HasPrefix(line, "PING") {
			fmt.Fprintf(c, "PONG%s\r\n", line[4:])
			continue
		}
		// :nick!nick@nick.tmi.twitch.tv PRIVMSG #channel :message
		prefix, rest, ok := strings.Cut(line, " PRIVMSG #")
		if !ok || !strings.HasPrefix(prefix, ":") {
			continue
		}
		channel, msg, ok := strings.Cut(rest, " :")
		user, _, _ := strings.Cut(prefix[1:], "!")
		if !ok || user == "nightbot" || strings.HasPrefix(msg, "!") {
			continue
		}
		msg = clean(msg)
		hover := text{"action": "show_text", "value": text{"text": channel, "color": "dark_purple"}}
		broadcastTwitch([]any{"",
			text{"text": "<" + user + "> ", "color": "dark_purple", "hoverEvent": hover},
			text{"text": msg, "color": "white", "hoverEvent": hover, "clickEvent": text{"action": "suggest_command", "value": msg}},
		})
	}
}

// clean drops control characters and the section sign (formatting codes).
func clean(s string) string {
	return strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f || r == '§' {
			return -1
		}
		return r
	}, s)
}
