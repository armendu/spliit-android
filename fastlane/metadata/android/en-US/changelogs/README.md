# Changelogs

One file per release, named after `appVersionCode` in `gradle/libs.versions.toml`, so `1.txt`
is the changelog for version code 1.

`.github/workflows/release.yml` puts the matching file at the top of the GitHub release notes.
A release without one still publishes; it just leads with the install instructions instead.

The path is [F-Droid's own convention](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/),
so the same files would serve an F-Droid listing without being written twice. Keep them plain
text and under about 500 characters, which is what F-Droid accepts.
