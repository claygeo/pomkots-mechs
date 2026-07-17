# pomkots-mechs

Please Read [Wikis](https://github.com/grc-mcs/pomkots-mechs/wiki)

## Verification

After Gradle dependencies are cached, run the complete noninteractive gate with
`./gradlew offlineCheck --offline`. It runs all ordinary subproject checks and
the Forge GameTest server; any failed GameTest makes the command fail. Ordinary
`./gradlew check` remains the fast unit-test gate and does not launch Minecraft.
