package com.mangle.retailshopapp.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when the container tests would be skipped, so that they cannot be
 * skipped quietly.
 *
 * <p>⚠ THIS TEST EXISTS BECAUSE A SKIP IS NOT A PASS, AND THIS PROJECT HAS NOW
 * BEEN BITTEN BY THAT TWICE.
 *
 * <p>Every migration test is {@code @Testcontainers(disabledWithoutDocker =
 * true)}, which is the right annotation — a developer with no Docker should not
 * get a wall of red. But its consequence is that ANY problem reaching Docker
 * turns 140 tests into skips and still prints {@code BUILD SUCCESS}. The
 * migration gate is the one thing standing between a mistyped column and a
 * production database, and it reports success by not running.
 *
 * <p>Twice now:
 *
 * <ul>
 *   <li>Docker Engine 29 rejected docker-java's default API 1.32, so
 *       {@code RechargeMigrationTest} skipped. Fixed with {@code api.version}
 *       in the POM and in {@code testcontainers.properties}.</li>
 *   <li>Under Colima, Ryuk could not bind-mount
 *       {@code ~/.colima/default/docker.sock} and died with "operation not
 *       supported", so all 140 skipped. Fixed with
 *       {@code TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE} in the POM's surefire
 *       environment.</li>
 * </ul>
 *
 * <p>Both were found by someone noticing a number, which is not a control. This
 * is the control: with no Docker and no explicit opt-out, the build goes red and
 * says what to do about it.
 *
 * <p>To run the rest of the suite without Docker, say so:
 * {@code mvn test -DskipContainerTests=true}.
 */
@DisplayName("the container gate")
class ContainerGateTest {

    @Test
    @DisplayName("⚠ Docker is reachable, so the migration gate is actually running")
    void dockerIsReachable() {
        if (Boolean.getBoolean("skipContainerTests")) {
            // Opted out on purpose. The flag is the whole point: skipping is a
            // decision somebody made rather than something that happened.
            return;
        }

        assertTrue(DockerClientFactory.instance().isDockerAvailable(), """
                Docker is not reachable, so every @Testcontainers test in this suite \
                would SKIP and the build would still pass — including the migration \
                gate, which is the only check that a migration applies to a \
                production-shaped database.

                Colima:         export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
                Docker Desktop: usually needs nothing
                Without Docker: mvn test -DskipContainerTests=true (and the gate does NOT run)

                See src/test/resources/testcontainers.properties and db/migration/README.md.""");
    }
}
