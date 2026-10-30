package com.youlong.hd;

final class ShellArgument {
    private ShellArgument() {}

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
