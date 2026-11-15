# mcprobe: the Java agent (share/java/mcprobe.jar), the live viewer (bin/mcprobe-web)
# and the in-game chat bridge (bin/mcprobe-chat).
{ lib, stdenvNoCC, buildGoModule, fetchurl, unzip, jdk8_headless }:
let
  # MCP names for the 1.12.2 server (used by mcprobe-web to translate profiles)
  srg = fetchurl {
    url = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.12.2/mcp-1.12.2-srg.zip";
    hash = "sha256-vqJyGIGESIUVlIib99mE32FP/GmLvkFZndYjfWJ+N4I=";
  };
  names = fetchurl {
    url = "https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp_stable/39-1.12/mcp_stable-39-1.12.zip";
    hash = "sha256-E6MfKMEfjzlf/n6FY63kWfWg7kZJOru9485ulJOsQVI=";
  };
  web = buildGoModule {
    pname = "mcprobe-web";
    version = "0.4.0";
    src = lib.fileset.toSource { root = ./web; fileset = ./web; };
    vendorHash = null; # standard library only
  };
  chat = buildGoModule {
    pname = "mcprobe-chat";
    version = "0.4.0";
    src = lib.fileset.toSource { root = ./chat; fileset = ./chat; };
    vendorHash = null;
  };
in
stdenvNoCC.mkDerivation {
  pname = "mcprobe";
  version = "0.4.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [ ./src ./scripts/build.sh ];
  };

  nativeBuildInputs = [ jdk8_headless unzip ];
  buildPhase = "sh scripts/build.sh";
  installPhase = ''
    install -Dm444 build/mcprobe.jar $out/share/java/mcprobe.jar
    install -Dm555 ${web}/bin/mcprobe-web $out/bin/mcprobe-web
    install -Dm555 ${chat}/bin/mcprobe-chat $out/bin/mcprobe-chat
    mkdir -p $out/share/mcprobe/mappings
    unzip -p ${srg} joined.srg > $out/share/mcprobe/mappings/joined.srg
    unzip -p ${names} methods.csv > $out/share/mcprobe/mappings/methods.csv
  '';

  meta = {
    description = "Java agent and live viewer for Minecraft 1.12.2 servers: tick times, chunks, on-demand profiling";
    license = lib.licenses.asl20;
    platforms = lib.platforms.linux;
  };
}
