{
  description = "Imagine3D - photo + idea -> printable 3D part, on-device (Android)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachSystem [ "x86_64-linux" ] (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        # Keep these in sync with app/build.gradle.kts
        versions = {
          platform = "37.0";
          buildTools = "36.0.0";
          ndk = "29.0.14206865";
          cmake = "3.31.6";
        };

        mkSdk = { withEmulator ? false }: pkgs.androidenv.composeAndroidPackages ({
          platformVersions = [ versions.platform ];
          buildToolsVersions = [ versions.buildTools ];
          includeNDK = true;
          ndkVersions = [ versions.ndk ];
          cmakeVersions = [ versions.cmake ];
          includeSources = false;
          includeExtras = [ ];
        } // pkgs.lib.optionalAttrs withEmulator {
          includeEmulator = true;
          includeSystemImages = true;
          systemImageTypes = [ "google_apis_playstore" ];
          abiVersions = [ "x86_64" ];
        });

        sdk = mkSdk { };
        sdkEmu = mkSdk { withEmulator = true; };

        # Host-side tooling used by the generation eval harness (tools/eval).
        llamaHost = pkgs.llama-cpp.override { vulkanSupport = true; };

        commonPkgs = [
          pkgs.jdk21
          pkgs.gradle
          pkgs.openscad-unstable
          llamaHost
          (pkgs.python3.withPackages (ps: [ ps.numpy ps.requests ps.trimesh ps.pillow ps.websocket-client ps.scipy ]))
          pkgs.chromium
          pkgs.nodejs_22
          pkgs.jq
          pkgs.unzip
          pkgs.curl
        ];

        mkShell = androidComposition: extraHook: pkgs.mkShell {
          packages = commonPkgs ++ [ androidComposition.androidsdk ];
          ANDROID_HOME = "${androidComposition.androidsdk}/libexec/android-sdk";
          ANDROID_SDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk";
          ANDROID_NDK_ROOT = "${androidComposition.androidsdk}/libexec/android-sdk/ndk/${versions.ndk}";
          JAVA_HOME = "${pkgs.jdk21}";
          shellHook = ''
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
            # Use the nix-patched aapt2 instead of the one AGP pulls from maven.
            export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/${versions.buildTools}/aapt2 ''${GRADLE_OPTS:-}"
          '' + extraHook;
        };
      in
      {
        devShells.default = mkShell sdk "";
        # Emulator + x86_64 Play Store image. AVDs live in ./.avd so nothing lands in ~/.android.
        devShells.emu = mkShell sdkEmu ''
          export ANDROID_AVD_HOME="$PWD/.avd"
          export ANDROID_EMULATOR_HOME="$PWD/.avd"
          mkdir -p "$ANDROID_AVD_HOME"
          export SYSTEM_IMAGE="system-images;android-${versions.platform};google_apis_playstore;x86_64"
        '';
      });
}
