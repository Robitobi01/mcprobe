package main

import (
	"errors"
	"math"
	"math/rand"
	"strconv"
	"strings"
)

// A small recursive-descent calculator for "=expr" chat messages:
// + - * / % and ^ or ** (power), parentheses, pi e tau, and
// sqrt pow sin cos tan asin acos atan floor ceil round log (base 10) ln abs factorial rng.

var consts = map[string]float64{"pi": math.Pi, "e": math.E, "tau": 2 * math.Pi}

var funcs = map[string]func([]float64) (float64, error){
	"sqrt": f1(math.Sqrt), "sin": f1(math.Sin), "cos": f1(math.Cos), "tan": f1(math.Tan),
	"asin": f1(math.Asin), "acos": f1(math.Acos), "atan": f1(math.Atan), "floor": f1(math.Floor),
	"ceil": f1(math.Ceil), "round": f1(math.Round), "log": f1(math.Log10), "ln": f1(math.Log), "abs": f1(math.Abs),
	"pow": func(a []float64) (float64, error) {
		if len(a) != 2 {
			return 0, errors.New("pow takes 2 arguments")
		}
		return math.Pow(a[0], a[1]), nil
	},
	"factorial": func(a []float64) (float64, error) {
		if len(a) != 1 || a[0] < 0 || a[0] != math.Floor(a[0]) || a[0] > 170 {
			return 0, errors.New("factorial takes an integer 0..170")
		}
		r := 1.0
		for i := 2.0; i <= a[0]; i++ {
			r *= i
		}
		return r, nil
	},
	"rng": func(a []float64) (float64, error) { return rand.Float64(), nil },
}

func f1(f func(float64) float64) func([]float64) (float64, error) {
	return func(a []float64) (float64, error) {
		if len(a) != 1 {
			return 0, errors.New("takes 1 argument")
		}
		return f(a[0]), nil
	}
}

type parser struct {
	s     string
	i     int
	depth int
}

func evalExpr(s string) (float64, error) {
	if len(s) > 200 {
		return 0, errors.New("too long")
	}
	p := &parser{s: strings.ToLower(s)}
	v, err := p.expr()
	if err == nil && p.peek() != 0 {
		err = errors.New("unexpected " + string(p.s[p.i]))
	}
	if err == nil && (math.IsNaN(v) || math.IsInf(v, 0)) {
		err = errors.New("not a number")
	}
	return v, err
}

func (p *parser) peek() byte {
	for p.i < len(p.s) && p.s[p.i] == ' ' {
		p.i++
	}
	if p.i < len(p.s) {
		return p.s[p.i]
	}
	return 0
}

func (p *parser) expr() (float64, error) {
	v, err := p.term()
	for err == nil {
		switch p.peek() {
		case '+':
			p.i++
			var r float64
			r, err = p.term()
			v += r
		case '-':
			p.i++
			var r float64
			r, err = p.term()
			v -= r
		default:
			return v, nil
		}
	}
	return v, err
}

func (p *parser) term() (float64, error) {
	v, err := p.unary()
	for err == nil {
		c := p.peek()
		if c == '*' && p.i+1 < len(p.s) && p.s[p.i+1] == '*' || c != '*' && c != '/' && c != '%' {
			return v, nil
		}
		p.i++
		var r float64
		if r, err = p.unary(); err != nil {
			break
		}
		switch c {
		case '*':
			v *= r
		case '/':
			v /= r
		case '%':
			v = math.Mod(v, r)
		}
	}
	return v, err
}

func (p *parser) unary() (float64, error) {
	switch p.peek() {
	case '-':
		p.i++
		v, err := p.unary()
		return -v, err
	case '+':
		p.i++
		return p.unary()
	}
	return p.power()
}

func (p *parser) power() (float64, error) {
	v, err := p.atom()
	if err != nil {
		return 0, err
	}
	switch {
	case p.peek() == '^':
		p.i++
	case strings.HasPrefix(p.s[p.i:], "**"):
		p.i += 2
	default:
		return v, nil
	}
	e, err := p.unary() // right-associative
	return math.Pow(v, e), err
}

func (p *parser) atom() (float64, error) {
	c := p.peek()
	switch {
	case c == '(':
		if p.depth++; p.depth > 50 {
			return 0, errors.New("too deep")
		}
		p.i++
		v, err := p.expr()
		p.depth--
		if err == nil && p.peek() != ')' {
			err = errors.New("missing )")
		}
		p.i++
		return v, err
	case c >= '0' && c <= '9' || c == '.':
		j := p.i
		for j < len(p.s) && (p.s[j] >= '0' && p.s[j] <= '9' || p.s[j] == '.') {
			j++
		}
		if j < len(p.s) && p.s[j] == 'e' && j+1 < len(p.s) && (p.s[j+1] >= '0' && p.s[j+1] <= '9' || p.s[j+1] == '-' || p.s[j+1] == '+') {
			j += 2
			for j < len(p.s) && p.s[j] >= '0' && p.s[j] <= '9' {
				j++
			}
		}
		v, err := strconv.ParseFloat(p.s[p.i:j], 64)
		p.i = j
		return v, err
	case c >= 'a' && c <= 'z':
		j := p.i
		for j < len(p.s) && p.s[j] >= 'a' && p.s[j] <= 'z' {
			j++
		}
		name := p.s[p.i:j]
		p.i = j
		if v, ok := consts[name]; ok {
			return v, nil
		}
		f, ok := funcs[name]
		if !ok || p.peek() != '(' {
			return 0, errors.New("unknown " + name)
		}
		p.i++
		var args []float64
		if p.peek() != ')' {
			for {
				p.depth++
				v, err := p.expr()
				p.depth--
				if err != nil {
					return 0, err
				}
				args = append(args, v)
				if p.peek() != ',' {
					break
				}
				p.i++
			}
		}
		if p.peek() != ')' {
			return 0, errors.New("missing )")
		}
		p.i++
		return f(args)
	}
	return 0, errors.New("syntax error")
}

// looksLikeMath: report errors only for messages that were meant as math, not "=)" etc.
func looksLikeMath(s string) bool {
	digit := false
	for _, r := range s {
		switch {
		case r >= '0' && r <= '9':
			digit = true
		case strings.ContainsRune("+-*/%^()., abcdefghijklmnopqrstuvwxyz", r):
		default:
			return false
		}
	}
	return digit
}

func formatNum(v float64) string {
	if math.Abs(v) > 1e18 {
		return strconv.FormatFloat(v, 'e', 2, 64)
	}
	return strconv.FormatFloat(math.Round(v*1e6)/1e6, 'f', -1, 64)
}
