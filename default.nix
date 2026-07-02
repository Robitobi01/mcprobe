{ pkgs ? import <nixpkgs> {} }:

pkgs.stdenv.mkDerivation {
  pname = "mspt-agent";
  version = "0.1.0";

  src = ./.;

  nativeBuildInputs = [ pkgs.jdk8 pkgs.makeWrapper ];

  buildPhase = ''
    runHook preBuild

    mkdir -p build/classes
    javac -source 8 -target 8 \
      -cp ${pkgs.jdk8}/lib/openjdk/lib/tools.jar \
      -d build/classes \
      src/main/java/net/dugged/mspt/MsptAgent.java

    cat > build/manifest.mf <<EOF
Manifest-Version: 1.0
Main-Class: net.dugged.mspt.MsptAgent
Premain-Class: net.dugged.mspt.MsptAgent
Agent-Class: net.dugged.mspt.MsptAgent
Can-Redefine-Classes: false
Can-Retransform-Classes: false
EOF

    jar cfm build/mspt-agent.jar build/manifest.mf -C build/classes .
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall

    mkdir -p $out/share/java $out/bin
    cp build/mspt-agent.jar $out/share/java/mspt-agent.jar
    makeWrapper ${pkgs.jdk8}/bin/java $out/bin/mspt-agent \
      --add-flags "-cp $out/share/java/mspt-agent.jar:${pkgs.jdk8}/lib/openjdk/lib/tools.jar net.dugged.mspt.MsptAgent"

    runHook postInstall
  '';
}
