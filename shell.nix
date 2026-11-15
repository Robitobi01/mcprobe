# nix-shell: JDK 8 for building, python for tests/integration.py
{ pkgs ? import <nixpkgs> { } }:
pkgs.mkShell {
  packages = [ pkgs.jdk8_headless pkgs.python3 pkgs.go ];
}
