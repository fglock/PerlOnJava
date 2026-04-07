#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Skip on Windows (different shell semantics)
my $is_windows = ($^O eq 'MSWin32' || $^O eq 'cygwin');

subtest 'IPC::Open3 basic stdout capture with readline' => sub {
    use IPC::Open3;
    my ($wtr, $rdr);
    $rdr = "";
    my $pid = open3($wtr, $rdr, undef, "echo", "hello-open3");
    close($wtr);
    my $line = <$rdr>;
    chomp $line if defined $line;
    is($line, "hello-open3", "readline captures stdout");
    close($rdr);
    waitpid($pid, 0);
    is($? >> 8, 0, "child exited cleanly");
};

subtest 'IPC::Open3 stdout capture with sysread' => sub {
    use IPC::Open3;
    my ($wtr, $rdr);
    $rdr = "";
    my $pid = open3($wtr, $rdr, undef, "echo", "sysread-test");
    close($wtr);
    my $buf;
    my $n = sysread($rdr, $buf, 4096);
    ok($n > 0, "sysread returned $n bytes");
    chomp $buf if defined $buf;
    is($buf, "sysread-test", "sysread captures stdout");
    close($rdr);
    waitpid($pid, 0);
};

subtest 'IPC::Open3 stderr capture (separate handle)' => sub {
    use IPC::Open3;
    my ($wtr, $rdr, $err);
    $rdr = ""; $err = "";
    my $pid = open3($wtr, $rdr, $err, "sh", "-c", "echo out-msg; echo err-msg >&2");
    close($wtr);
    # Small delay to let both streams fill
    select(undef, undef, undef, 0.3);
    my $out = <$rdr>;
    my $errout = <$err>;
    chomp $out if defined $out;
    chomp $errout if defined $errout;
    is($out, "out-msg", "stdout captured separately");
    is($errout, "err-msg", "stderr captured separately");
    close($rdr);
    close($err);
    waitpid($pid, 0);
};

subtest 'IPC::Open3 stderr merged with stdout (undef err)' => sub {
    use IPC::Open3;
    my ($wtr, $rdr);
    my $pid = open3($wtr, $rdr, undef, "sh", "-c", "echo out-msg; echo err-msg >&2");
    close($wtr);
    my @lines;
    while (my $line = <$rdr>) {
        chomp $line;
        push @lines, $line;
    }
    close($rdr);
    waitpid($pid, 0);
    # Both stdout and stderr should appear in the reader
    ok(scalar(@lines) >= 2, "got at least 2 lines (merged streams)");
    ok(grep({ $_ eq "out-msg" } @lines), "stdout present in merged output");
    ok(grep({ $_ eq "err-msg" } @lines), "stderr present in merged output");
};

subtest 'IPC::Open3 fileno returns defined value' => sub {
    use IPC::Open3;
    my ($wtr, $rdr, $err);
    $rdr = ""; $err = "";
    my $pid = open3($wtr, $rdr, $err, "cat");

    my $fn_wtr = fileno($wtr);
    my $fn_rdr = fileno($rdr);
    my $fn_err = fileno($err);

    ok(defined($fn_wtr), "write handle has fileno: $fn_wtr");
    ok(defined($fn_rdr), "read handle has fileno: $fn_rdr");
    ok(defined($fn_err), "error handle has fileno: $fn_err");
    ok($fn_wtr != $fn_rdr, "write and read have different filenos");
    ok($fn_rdr != $fn_err, "read and error have different filenos");

    close($wtr);
    close($rdr);
    close($err);
    waitpid($pid, 0);
};

subtest 'IPC::Open3 with IO::Select - single handle' => sub {
    use IPC::Open3;
    use IO::Select;
    my ($wtr, $rdr);
    $rdr = "";
    my $pid = open3($wtr, $rdr, undef, "echo", "select-test");
    close($wtr);

    my $sel = IO::Select->new($rdr);
    is($sel->count, 1, "IO::Select has 1 handle");

    my @ready = $sel->can_read(5);
    ok(scalar(@ready) > 0, "can_read returned ready handles");

    my $buf;
    my $n = sysread($ready[0], $buf, 4096);
    ok($n > 0, "sysread got data from select-ready handle");
    chomp $buf if defined $buf;
    is($buf, "select-test", "correct data from IO::Select");

    close($rdr);
    waitpid($pid, 0);
};

subtest 'IPC::Open3 with IO::Select - stdout + stderr (Net::SSH pattern)' => sub {
    use IPC::Open3;
    use IO::Select;
    my ($wtr, $rdr, $err);
    $rdr = ""; $err = "";
    my $pid = open3($wtr, $rdr, $err, "sh", "-c", "echo stdout-data; echo stderr-data >&2");
    close($wtr);

    my $sel = IO::Select->new();
    $sel->add($rdr);
    $sel->add($err);
    is($sel->count, 2, "IO::Select has 2 handles");

    my ($out, $errout) = ("", "");
    my $iterations = 0;
    while ($sel->count && $iterations < 20) {
        $iterations++;
        my @ready = $sel->can_read(2);
        last if !@ready;
        for my $fh (@ready) {
            my $buf;
            my $n = sysread($fh, $buf, 4096);
            if (!$n) {
                $sel->remove($fh);
                next;
            }
            if (fileno($fh) == fileno($rdr)) {
                $out .= $buf;
            } else {
                $errout .= $buf;
            }
        }
    }

    chomp $out; chomp $errout;
    is($out, "stdout-data", "IO::Select captured stdout");
    is($errout, "stderr-data", "IO::Select captured stderr");

    close($rdr);
    close($err);
    waitpid($pid, 0);
};

subtest 'IPC::Open2 basic round-trip' => sub {
    use IPC::Open2;
    my ($rdr, $wtr);
    my $pid = open2($rdr, $wtr, "cat");

    ok(defined($pid), "open2 returned pid");
    ok($pid > 0, "pid is positive");

    print $wtr "round-trip-test\n";
    close($wtr);
    my $line = <$rdr>;
    chomp $line if defined $line;
    is($line, "round-trip-test", "open2 round-trip works");
    close($rdr);
    waitpid($pid, 0);
    is($? >> 8, 0, "child exited cleanly");
};

subtest 'IPC::Open2 with syswrite and sysread' => sub {
    use IPC::Open2;
    my ($rdr, $wtr);
    my $pid = open2($rdr, $wtr, "cat");

    my $written = syswrite($wtr, "syswrite-test\n");
    ok($written > 0, "syswrite wrote $written bytes");
    close($wtr);

    my $buf;
    my $n = sysread($rdr, $buf, 4096);
    ok($n > 0, "sysread read $n bytes");
    chomp $buf if defined $buf;
    is($buf, "syswrite-test", "syswrite/sysread round-trip works");

    close($rdr);
    waitpid($pid, 0);
};

subtest 'waitpid after open3' => sub {
    use IPC::Open3;
    my ($wtr, $rdr);
    $rdr = "";
    my $pid = open3($wtr, $rdr, undef, "sh", "-c", "exit 42");
    close($wtr);
    # Drain output
    while (<$rdr>) {}
    close($rdr);
    my $waited = waitpid($pid, 0);
    is($waited, $pid, "waitpid returned correct pid");
    is($? >> 8, 42, "child exit status captured correctly");
};

subtest 'IPC::Open3 gensym pattern (from SYNOPSIS)' => sub {
    use IPC::Open3;
    use Symbol 'gensym';
    my $pid = open3(my $chld_in, my $chld_out, my $chld_err = gensym,
                    "sh", "-c", "echo out-gensym; echo err-gensym >&2");
    close($chld_in);
    # Small delay to let both streams fill
    select(undef, undef, undef, 0.3);
    my $out = <$chld_out>;
    my $err = <$chld_err>;
    chomp $out if defined $out;
    chomp $err if defined $err;
    is($out, "out-gensym", "stdout via gensym pattern");
    is($err, "err-gensym", "stderr via gensym pattern");
    close($chld_out);
    close($chld_err);
    waitpid($pid, 0);
};

subtest 'IPC::Open3 with IO::File objects and IO::Select (Net::SSH pattern)' => sub {
    use IPC::Open3;
    use IO::File;
    use IO::Select;
    use POSIX ":sys_wait_h";

    my $reader = IO::File->new();
    my $writer = IO::File->new();
    my $error  = IO::File->new();

    my $pid = open3($writer, $reader, $error, "sh", "-c", "echo stdout-io; echo stderr-io >&2");
    close($writer);

    my $select = IO::Select->new();
    $select->add($reader);
    $select->add($error);
    is($select->count, 2, "IO::Select has 2 IO::File handles");

    my ($out, $errout) = ("", "");
    my $iterations = 0;
    while ($select->count && $iterations < 20) {
        $iterations++;
        my @ready = $select->can_read(2);
        last if !@ready;
        for my $fh (@ready) {
            my $buf;
            my $n = sysread($fh, $buf, 4096);
            if (!$n) {
                $select->remove($fh);
                next;
            }
            if (fileno($fh) == fileno($reader)) {
                $out .= $buf;
            } elsif (fileno($fh) == fileno($error)) {
                $errout .= $buf;
            }
        }
    }

    chomp $out; chomp $errout;
    is($out, "stdout-io", "IO::File + IO::Select captured stdout");
    is($errout, "stderr-io", "IO::File + IO::Select captured stderr");

    close($reader);
    close($error);
    waitpid($pid, WNOHANG);
};

subtest 'IPC::Open3 with typeglob handles' => sub {
    use IPC::Open3;
    # Use bareword filehandles (typeglobs) like Net::SSH does:
    #   open3(\*WRITER, \*READER, \*ERROR, @cmd)
    my $pid = open3(\*WTR_GLOB, \*RDR_GLOB, \*ERR_GLOB,
                    "sh", "-c", "echo glob-out; echo glob-err >&2");
    close(WTR_GLOB);
    # Small delay to let both streams fill
    select(undef, undef, undef, 0.3);
    my $out = <RDR_GLOB>;
    my $err = <ERR_GLOB>;
    chomp $out if defined $out;
    chomp $err if defined $err;
    is($out, "glob-out", "stdout via typeglob handle");
    is($err, "glob-err", "stderr via typeglob handle");
    close(RDR_GLOB);
    close(ERR_GLOB);
    waitpid($pid, 0);
    is($? >> 8, 0, "child exited cleanly with typeglob handles");
};

subtest 'IPC::Open3 single string command (shell interpretation)' => sub {
    use IPC::Open3;
    my $pid = open3(my $in, my $out, undef, "echo hello && echo world");
    close($in);
    my @lines;
    while (<$out>) { s/\s+$//; push @lines, $_; }
    close($out);
    waitpid($pid, 0);
    is(scalar(@lines), 2, "shell interpreted && correctly");
    is($lines[0], "hello", "first command output");
    is($lines[1], "world", "second command output");
};

done_testing();
