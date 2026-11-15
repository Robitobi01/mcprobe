package main

import "testing"

func TestCalc(t *testing.T) {
	for in, want := range map[string]string{
		"1+2*3": "7", "(1+2)*3": "9", "2^10": "1024", "2**3**2": "512", "-2^2": "-4",
		"sqrt(16)+pow(2,3)": "12", "10/3": "3.333333", "7%4": "3", "factorial(5)": "120",
		"log(1000)": "3", "pi": "3.141593", "1e3/4": "250", "2^100": "1.27e+30", "floor(2.7)": "2",
	} {
		v, err := evalExpr(in)
		if err != nil || formatNum(v) != want {
			t.Errorf("%s = %v (%v), want %s", in, formatNum(v), err, want)
		}
	}
	for _, in := range []string{"", ")", "1+", "foo(1)", "1/0", "sqrt(", "(((("} {
		if _, err := evalExpr(in); err == nil {
			t.Errorf("%q: expected error", in)
		}
	}
}
