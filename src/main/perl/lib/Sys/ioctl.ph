# sys/ioctl.ph - stub for PerlOnJava
# Platform-aware ioctl constants.
# ioctl() itself is a no-op stub on JVM, so these values are never actually
# used for real system calls - they exist for module compatibility.

if ($^O eq 'darwin') {
    unless (defined &TIOCSWINSZ) {
        eval 'sub TIOCSWINSZ () { 0x80087467; }';
    }
    unless (defined &TIOCGWINSZ) {
        eval 'sub TIOCGWINSZ () { 0x40087468; }';
    }
    unless (defined &FIONREAD) {
        eval 'sub FIONREAD () { 0x4004667F; }';
    }
    unless (defined &FIONBIO) {
        eval 'sub FIONBIO () { 0x8004667E; }';
    }
} else {
    # Linux / default
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
}
1;
