# sys/ioctl.ph - stub for PerlOnJava
# These are standard ioctl constants. Values are for Linux compatibility.
# ioctl() itself is a no-op stub on JVM, so these values are never actually
# used for real system calls.

unless (defined &TIOCSWINSZ) {
    eval 'sub TIOCSWINSZ () { 0x5414; }';
}
unless (defined &TIOCGWINSZ) {
    eval 'sub TIOCGWINSZ () { 0x5413; }';
}
unless (defined &FIONREAD) {
    eval 'sub FIONREAD () { 0x541B; }';
}
unless (defined &FIONBIO) {
    eval 'sub FIONBIO () { 0x5421; }';
}
1;
