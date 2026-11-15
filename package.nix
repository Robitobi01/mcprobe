{ lib, stdenvNoCC, jdk8_headless }:
stdenvNoCC.mkDerivation {
  pname = "mcprobe";
  version = "0.3.0";

  src = lib.fileset.toSource {
    root = ./.;
    fileset = lib.fileset.unions [ ./src ./scripts/build.sh ];
  };

  nativeBuildInputs = [ jdk8_headless ];
  buildPhase = "sh scripts/build.sh";
  installPhase = "install -Dm444 build/mcprobe.jar $out/share/java/mcprobe.jar";

  meta = {
    description = "Java agent publishing tick times and loaded chunks of Minecraft 1.12.2 servers to files";
    platforms = lib.platforms.all;
  };
}
