#!/usr/bin/env bash
# Builds the Media Center plugin: the zip Jellyfin installs, kept in plugin/repo/ (so the plugin
# repository works straight from GitHub, with no release to upload), and manifest.json (the
# repository listing) with this version's entry and checksum added.
#
#   plugin/build.sh [changelog]
#
# Needs the .NET 10 SDK. The version comes from AssemblyVersion in the .csproj.
set -euo pipefail
cd "$(dirname "$0")"

project=Jellyfin.Plugin.MediaCenter
version=$(sed -n 's:.*<AssemblyVersion>\(.*\)</AssemblyVersion>.*:\1:p' "$project/$project.csproj")
target_abi="12.1.0.0"
repo="Madmaxx636/MediaCenter-Jellyfin"
changelog="${1:-See the release notes on GitHub.}"
zip_name="jellyfin-plugin-mediacenter_${version}.zip"

rm -rf dist/build && mkdir -p dist/build
dotnet publish "$project" -c Release -o dist/build --nologo -v quiet
mkdir -p repo
rm -f "repo/$zip_name"
(cd dist/build && zip -q -X "../../repo/$zip_name" "$project.dll")
checksum=$(md5sum "repo/$zip_name" | cut -d' ' -f1)

python3 - "$version" "$target_abi" "$repo" "$zip_name" "$checksum" "$changelog" <<'EOF'
import json, os, sys, datetime
version, abi, repo, zip_name, checksum, changelog = sys.argv[1:]
path = "manifest.json"
manifest = json.load(open(path)) if os.path.exists(path) else [{
    "guid": "9167cfe0-b0f6-4832-8848-0c9d3e677bc7",
    "name": "Media Center",
    "description": "Control and brand the Media Center app for Android TV.",
    "overview": "Default and locked settings for the Media Center app (for everyone or per user), start menu notices, update notices, and your server's own startup chime, sounds, logo, intro title, backdrop and accent colour.",
    "owner": "Madmaxx636",
    "category": "General",
    "versions": [],
}]
entry = {
    "version": version,
    "changelog": changelog,
    "targetAbi": abi,
    "sourceUrl": f"https://raw.githubusercontent.com/{repo}/main/plugin/repo/{zip_name}",
    "checksum": checksum,
    "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
}
plugin = manifest[0]
plugin["versions"] = [entry] + [v for v in plugin["versions"] if v["version"] != version]
json.dump(manifest, open(path, "w"), indent=2)
print(f"repo/{zip_name} (md5 {checksum}); manifest.json lists {len(plugin['versions'])} version(s)")
EOF
