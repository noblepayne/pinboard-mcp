{
  description = "Pinboard MCP Server — Babashka Streamable HTTP MCP server for the Pinboard API";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = {
    self,
    nixpkgs,
    flake-utils,
  }:
    flake-utils.lib.eachDefaultSystem (system: let
      pkgs = nixpkgs.legacyPackages.${system};
      bb = pkgs.babashka;

      pinboard-mcp = pkgs.stdenv.mkDerivation {
        name = "pinboard-mcp";
        version = self.shortRev or "dirty";
        src = ./pinboard_mcp.bb; # Single file project — no filter needed

        nativeBuildInputs = [bb pkgs.makeWrapper];

        dontBuild = true;
        dontUnpack = true; # Single file, no archive to unpack

        installPhase = ''
          mkdir -p $out/share/pinboard-mcp
          cp $src $out/share/pinboard-mcp/pinboard_mcp.bb
          makeWrapper ${bb}/bin/bb $out/bin/pinboard-mcp \
            --add-flags "$out/share/pinboard-mcp/pinboard_mcp.bb"
        '';

        meta = with pkgs.lib; {
          description = "Pinboard MCP Server";
          homepage = "https://github.com/anomalyco/pinboard-mcp";
          license = licenses.mit;
          platforms = platforms.linux;
        };
      };
    in {
      formatter = pkgs.alejandra;
      packages = {
        default = pinboard-mcp;
        inherit pinboard-mcp;
      };

      devShells.default = pkgs.mkShell {
        buildInputs = [bb pkgs.clj-kondo pkgs.jq];
        shellHook = ''
          echo "pinboard-mcp dev shell"
          echo "  bb run      — start server (OS-assigned port)"
          echo "  bb start    — start server on port 3030"
          echo "  bb test     — run tests"
          echo "  bb lint     — clj-kondo lint"
          echo ""
          echo "Auth: set PINBOARD_TOKEN"
          echo "      or write ~/.config/pinboard/config.edn"
        '';
      };
    })
    // {
      # System-agnostic outputs (outside eachDefaultSystem)
      nixosModules.default = {
        config,
        lib,
        pkgs,
        ...
      }: let
        cfg = config.services.pinboard-mcp;
        # Reference the package via self, not via the let-bound name from eachDefaultSystem
        pkg = self.packages.${pkgs.stdenv.hostPlatform.system}.pinboard-mcp;
      in {
        options.services.pinboard-mcp = {
          enable = lib.mkEnableOption "Pinboard MCP Server";

          port = lib.mkOption {
            type = lib.types.int;
            default = 3030;
            description = "Port for the Pinboard MCP server to listen on.";
          };

          tokenFile = lib.mkOption {
            type = lib.types.path;
            description = ''
              Path to a file containing Pinboard credentials in the format:
                PINBOARD_TOKEN=username:token
              Keep this file outside the Nix store (e.g. /run/secrets/pinboard).
            '';
          };
        };

        config = lib.mkIf cfg.enable {
          systemd.services.pinboard-mcp = {
            description = "Pinboard MCP Server";
            wantedBy = ["multi-user.target"];
            after = ["network.target"];

            serviceConfig = {
              Type = "simple";
              ExecStart = "${pkg}/bin/pinboard-mcp";
              Restart = "on-failure";
              RestartSec = "5s";

              # Load token securely from secret file (format: PINBOARD_TOKEN=...)
              EnvironmentFile = cfg.tokenFile;
              Environment = [
                "PINBOARD_MCP_PORT=${toString cfg.port}"
              ];

              # Use an ephemeral system user (no pre-creation needed)
              DynamicUser = true;

              # Hardening
              NoNewPrivileges = true;
              PrivateTmp = true;
              ProtectSystem = "strict";
              ProtectHome = "yes"; # Consistent with DynamicUser
            };
          };
        };
      };
    };
}
