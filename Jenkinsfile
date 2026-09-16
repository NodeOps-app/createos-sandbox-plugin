/*
 * Build on ci.jenkins.io.
 *
 * JDK 21 is the floor: pom's jenkins.version is 2.479.3, whose Java support policy is
 * 17 or 21. JDK 25 covers the other end — the newest version current LTS lines accept.
 * Building both proves the plugin compiles across the whole supported range rather than
 * only at the version whoever last touched it happened to have installed.
 */
buildPlugin(
    useContainerAgent: true,
    configurations: [
        [platform: 'linux', jdk: 21],
        [platform: 'linux', jdk: 25],
    ])
