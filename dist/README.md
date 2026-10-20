# dist/

Built APKs are copied here by `scripts/build-debug.ps1` / `scripts/build-debug.sh` (`dist/CactusAI-debug.apk`).
Local builds stay git-ignored, so building on your own PC never changes the repository.

If `CactusAI-debug.apk` and `BUILD-INFO.txt` are tracked in the repository, they were added by the `build`
workflow's manual `commit_apk` option, from the Windows runner's debug build. `BUILD-INFO.txt` says which commit
and run produced the file and gives its SHA-256. The APK contains the Needle engine, which its Hugging Face
repository declares as Apache-2.0 (see ../NOTICE.md). It is signed with a throwaway debug key and has not been run on a phone.
