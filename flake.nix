{
  description = "Butan - a minimal store-and-forward, age-encrypted NFC messenger";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      forAllSystems =
        f:
        nixpkgs.lib.genAttrs systems (
          system:
          f (
            import nixpkgs {
              inherit system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            }
          )
        );
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          android = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "latest";
            platformToolsVersion = "latest";
            buildToolsVersions = [ "36.0.0" ];
            platformVersions = [ "37.0" ];
            includeEmulator = false;
            includeSources = false;
            includeSystemImages = false;
            includeNDK = false;
            includeCmake = false;
            useGoogleAPIs = false;
            useGoogleTVAddOns = false;
          };
          sdk = android.androidsdk;
          jdk = pkgs.jdk17;
        in
        {
          default = pkgs.mkShell {
            packages = [
              jdk
              pkgs.gradle_9
              pkgs.age
              pkgs.git
              pkgs.jq
              pkgs.unzip
              pkgs.which
              sdk
            ];

            JAVA_HOME = "${jdk}/lib/openjdk";
            ANDROID_HOME = "${sdk}/libexec/android-sdk";
            ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
            GRADLE_USER_HOME = "${toString ./.}/.gradle";

            shellHook = ''
              export PATH="${sdk}/libexec/android-sdk/platform-tools:${sdk}/libexec/android-sdk/cmdline-tools/latest/bin:$PATH"
              echo "butan dev shell"
              echo "  gradle:  $(which gradle)"
              echo "  java:    $JAVA_HOME"
              echo "  sdk:     $ANDROID_HOME"
              echo "  age:     $(age --version)"
            '';
          };
        }
      );

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}
