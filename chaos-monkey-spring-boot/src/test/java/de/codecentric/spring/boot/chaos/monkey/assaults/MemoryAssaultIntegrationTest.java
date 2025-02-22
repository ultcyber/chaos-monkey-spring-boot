package de.codecentric.spring.boot.chaos.monkey.assaults;

import de.codecentric.spring.boot.chaos.monkey.configuration.ChaosMonkeySettings;
import de.codecentric.spring.boot.chaos.monkey.endpoints.AssaultPropertiesUpdate;
import de.codecentric.spring.boot.demo.chaos.monkey.ChaosDemoApplication;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import javax.validation.constraints.NotNull;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * @author Benjamin Wilms
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = ChaosDemoApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=chaosmonkey",
        "management.endpoints.enabled-by-default=true",
        "chaos.monkey.assaults.memoryActive=true",
        "chaos.monkey.assaults.memoryFillTargetFraction=0.80",
        "chaos.monkey.assaults.memoryMillisecondsWaitNextIncrease=100",
        "chaos.monkey.assaults.memoryFillIncrementFraction=0.99",
        "chaos.monkey.assaults.memoryMillisecondsHoldFilledMemory=2000",
        "spring.profiles.active=chaos-monkey"})
public class MemoryAssaultIntegrationTest {
    @LocalServerPort
    private int serverPort;

    private String baseUrl;

    @Autowired
    private TestRestTemplate restTemplate;


    @Autowired
    private MemoryAssault memoryAssault;

    @Autowired
    private ChaosMonkeySettings settings;

    @NotNull
    private boolean isMemoryAssaultActiveOriginal;

    @NotNull
    private double memoryFillTargetFraction;

    @Before
    public void setUp() {
        isMemoryAssaultActiveOriginal = settings.getAssaultProperties().isMemoryActive();
        memoryFillTargetFraction =
                settings.getAssaultProperties().getMemoryFillTargetFraction();
        baseUrl = "http://localhost:" + this.serverPort + "/actuator/chaosmonkey";
    }

    @After
    public void tearDown() {
        settings.getAssaultProperties().setMemoryActive(isMemoryAssaultActiveOriginal);
    }

    @Test
    public void memoryAssault_configured() {
        assertNotNull(memoryAssault);
        assertTrue(memoryAssault.isActive());
    }

    @Test
    public void runAttack() {
        Runtime rt = Runtime.getRuntime();
        long start = System.nanoTime();

        Thread backgroundThread = new Thread(memoryAssault::attack);
        backgroundThread.start();

        // make sure we timeout if we never reach the target fill fraction
        while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30)) {
            // if we reach target approximately (memory filled up
            // correctly) we can return (test is successful)
            double target = rt.maxMemory() * memoryFillTargetFraction;
            if (isInRange(rt.totalMemory(), target, 0.2)) {
                return;
            }
        }

        // if timeout reached
        fail("Memory did not fill up in time. Filled " + SizeConverter.toMegabytes(rt.totalMemory())
                + " MB but should have filled "
                + SizeConverter.toMegabytes(rt.maxMemory() * memoryFillTargetFraction) + " MB");


    }

    /**
     * Checks if `value` is in range of designated `target`, depending on
     * given `deviationFactor`
     *
     * @param value           value to check against target if its in range
     * @param deviationFactor factor in percentage (10% = 0.1) of how much
     *                        value is allowed to deviate from target
     * @param target          value against value is checked against
     * @return true if in range
     */
    private boolean isInRange(double value, double target, double deviationFactor) {
        double deviation = target * deviationFactor;
        double lowerBoundary = Math.max(target - deviation, 0);
        double upperBoundary = Math.max(target + deviation,
                Runtime.getRuntime().maxMemory());

        return value >= lowerBoundary && value <= upperBoundary;
    }


    @SuppressWarnings("StatementWithEmptyBody")
    @Test
    public void runAndAbortAttack() throws Throwable {
        AssaultPropertiesUpdate assaultProperties = new AssaultPropertiesUpdate();
        assaultProperties.setMemoryActive(false);

        Runtime rt = Runtime.getRuntime();
        long start = System.nanoTime();

        long usedMemoryBeforeAttack = rt.totalMemory() - rt.freeMemory();
        Thread backgroundThread = new Thread(memoryAssault::attack);

        backgroundThread.start();
        Thread.sleep(100);
        long usedMemoryDuringAttack = rt.totalMemory() - rt.freeMemory();

        assertTrue(usedMemoryBeforeAttack <= usedMemoryDuringAttack);

        ResponseEntity<String> result =
                restTemplate.postForEntity(baseUrl + "/assaults", assaultProperties,
                        String.class);
        assertEquals(200, result.getStatusCodeValue());


        while (backgroundThread.isAlive() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30)) {
            // wait for thread to finish gracefully or time out
        }

        assertFalse("Assault is still running", backgroundThread.isAlive());

        // TODO: Check again when JAVA 8 can be dropped.
        // Apparently java 8 needs a bit more time to finish up things
        Thread.sleep(1000);

        long usedMemoryAfterAttack = rt.totalMemory() - rt.freeMemory();

        // garbage collection should have ran by now
        assertTrue("Memory after attack was " + SizeConverter.toMegabytes(usedMemoryAfterAttack) + " MB but should have been less  amount of memory during attack (" + SizeConverter.toMegabytes(usedMemoryDuringAttack) + " MB).",
                usedMemoryAfterAttack <= usedMemoryDuringAttack);
    }

    @Test
    public void allowInterruptionOfAssaultDuringHoldPeriod() throws Throwable {
        AssaultPropertiesUpdate assaultProperties = new AssaultPropertiesUpdate();
        assaultProperties.setMemoryActive(false);

        Runtime rt = Runtime.getRuntime();
        long start = System.nanoTime();

        Thread backgroundThread = new Thread(memoryAssault::attack);
        assertFalse("Assault already active", backgroundThread.isAlive());

        backgroundThread.start();
        assertTrue("Assault not active", backgroundThread.isAlive());

        outer:
        {
            double fillTargetMemory = rt.maxMemory() * memoryFillTargetFraction;
            while (System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30)) {
                long totalMemoryDuringAttack = rt.totalMemory();
                if (isInRange(totalMemoryDuringAttack, fillTargetMemory, 0.2)) {
                    break outer;
                }
            }

            fail("Memory did not fill up in time. Filled " + SizeConverter.toMegabytes(rt.totalMemory())
                    + " MB but should have filled "
                    + SizeConverter.toMegabytes(fillTargetMemory) + " MB");
        }

        ResponseEntity<String> result =
                restTemplate.postForEntity(baseUrl + "/assaults", assaultProperties,
                        String.class);
        assertEquals("Request was not successful", 200,
                result.getStatusCodeValue());

        // TODO: Check again when JAVA 8 can be dropped.
        // Apparently java 8 needs a bit more time to finish up things
        Thread.sleep(1000);

        assertFalse("Assault is still running", backgroundThread.isAlive());
    }

}
