package Win32;

# placeholder

# All Windows versions supported by the JVM are members of the NT family.
# Core Win32 exposes this predicate and build tooling such as IPC::Cmd calls it
# while deciding which process backend is safe to use.
sub IsWinNT {
    return 1;
}

sub GetOSVersion {
}

1;
