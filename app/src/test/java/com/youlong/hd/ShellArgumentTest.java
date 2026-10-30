package com.youlong.hd;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ShellArgumentTest {
    @Test public void quotesPackageNamesAndShellMetacharacters() {
        assertEquals("'com.example.app'", ShellArgument.quote("com.example.app"));
        assertEquals("'a'\\''; touch /tmp/pwn'", ShellArgument.quote("a'; touch /tmp/pwn"));
        assertEquals("''", ShellArgument.quote(""));
    }
}
