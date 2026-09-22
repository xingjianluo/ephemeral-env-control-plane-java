package com.github.xingjianluo.ephemeralenvcontrolplanejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Context loads and all beans (services, scheduler, seeder) wire correctly. */
@SpringBootTest(properties = "cp.reaper-interval-millis=31536000000")
public class ApplicationTests {

    @Test
    public void contextLoads() {
    }
}
