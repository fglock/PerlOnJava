#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 31;
use File::Spec;

{
    package BranchBlessGuard;

    sub new {
        if (0) {
            bless [], shift;
        }
        else {
            bless [], shift;
        }
    }

    sub DESTROY {
        $main::branch_bless_destroyed++;
    }
}

my $guard = BranchBlessGuard->new;
is($main::branch_bless_destroyed, undef, 'blessed value returned from branch survives caller assignment');
undef $guard;
is($main::branch_bless_destroyed, 1, 'branch-returned blessed value is destroyed after caller releases it');

ok(!defined &{"main::missing_io_compress_regression_sub"}, 'defined &{string} is allowed under strict refs for missing subs');

sub existing_io_compress_regression_sub { 1 }
ok(defined &{"main::existing_io_compress_regression_sub"}, 'defined &{string} is true under strict refs for existing subs');

{
    package ImportedPrototypeRegression;
    sub gzlike ($$) { 1 }
}
*main::gzlike_imported_regression = \&ImportedPrototypeRegression::gzlike;
eval 'gzlike_imported_regression()';
like($@, qr/Not enough arguments for ImportedPrototypeRegression::gzlike\b/,
     'imported prototype errors report the original CV name');

{
    my $chars = "a\xFF\x{100}";
    my $octets = "\x61\xC3\xBF\xC4\x80";
    ok(!($chars eq $octets), 'plain string eq compares characters for UTF-8 scalars');
    {
        use bytes;
        ok($chars eq $octets, 'use bytes makes string eq compare UTF-8 octets');
    }
}

{
    my $char = pack("W*", 0x100);
    utf8::upgrade($char);
    if (1) {
        use bytes;
        my $bytes = CORE::fc($char);
        is(unpack("H*", $bytes), 'c480', 'use bytes folds non-ASCII as UTF-8 bytes inside block');
    }
    my $copy = "" . $char;
    utf8::encode($copy);
    is(unpack("H*", $copy), 'c480', 'use bytes block does not leak into later concatenation');
}

ok($Compress::Zlib::VERSION || do { require Compress::Zlib; $Compress::Zlib::VERSION }, 'Compress::Zlib reports a version');

use IO::Handle qw(SEEK_SET SEEK_CUR SEEK_END);
is_deeply([SEEK_SET, SEEK_CUR, SEEK_END], [0, 1, 2], 'IO::Handle exports seek constants on request');

require IO::File;
my $autoflush_fh = IO::File->new_tmpfile;
is($autoflush_fh->autoflush(1), 0, 'IO::Handle autoflush returns initial false state');
is($autoflush_fh->autoflush(1), 1, 'IO::Handle autoflush returns previous true state');

{
    my $stdin_data = "stdin regression\n";
    SKIP: {
        open(SAVE_STDIN_REGRESSION, "<&STDIN")
            or skip "dup STDIN failed: $!", 1;
        open(STDIN, "<", \$stdin_data)
            or do {
                my $err = $!;
                open(STDIN, "<&SAVE_STDIN_REGRESSION") or die "restore STDIN failed: $!";
                close SAVE_STDIN_REGRESSION;
                skip "reopen STDIN to scalar failed: $err", 1;
            };
        is(fileno(STDIN), 0, 'reopening STDIN preserves file descriptor 0');
        open(STDIN, "<&SAVE_STDIN_REGRESSION") or die "restore STDIN failed: $!";
        close SAVE_STDIN_REGRESSION;
    }
}

{
    package OverloadedHandleRegression;
    use overload '*{}' => sub { $_[0]->{fh} }, fallback => 1;
    sub new { bless { fh => $_[1] }, $_[0] }
}

{
    my $data = "x";
    open(my $fh, "<", \$data) or die "scalar open failed: $!";
    my $obj = OverloadedHandleRegression->new($fh);
    ok(!eof($obj), 'eof resolves overloaded filehandle objects');
    read($fh, my $buf, 1);
    ok(eof($obj), 'eof sees EOF through overloaded filehandle object');
}

my $no_pre_esc = q{(?<![${BEGIN_DELIM}])};
my $glob_pattern = 'foo#1';
$glob_pattern =~ s/${no_pre_esc}#(\d)/bar/g;
is($glob_pattern, 'foobar', 'lookbehind length validation ignores literal braces in character classes');

require File::Glob;
File::Glob->import(':glob');
*File::GlobMapperRegression::globber = \&File::Glob::csh_glob;
ok(defined &File::GlobMapperRegression::globber, 'File::Glob provides csh_glob for File::GlobMapper');
{
    my $dir = "io_compress_glob_order_$$";
    mkdir $dir or die "mkdir $dir failed: $!";
    for my $name (qw(a2 a3 a1)) {
        open(my $fh, '>', "$dir/$name.tmp") or die "create glob fixture failed: $!";
        print {$fh} $name;
    }
    my @expected_glob = map { File::Spec->catfile($dir, "$_.tmp") } qw(a1 a2 a3);
    is_deeply([File::Glob::csh_glob("$dir/a*.tmp")],
              \@expected_glob,
              'File::Glob csh_glob sorts matches by default');
    is_deeply([glob("$dir/a*.tmp")],
              \@expected_glob,
              'CORE glob sorts matches by default');
    unlink "$dir/a1.tmp", "$dir/a2.tmp", "$dir/a3.tmp";
    rmdir $dir;
}

{
    my $fh = IO::File->new_tmpfile or die "tmpfile failed: $!";
    local $\ = "\n";
    $fh->write("hello");
    seek($fh, 0, 0) or die "seek tmpfile failed: $!";
    my $written = <$fh>;
    is($written, 'hello', 'IO::Handle::write ignores output record separator');
}

{
    my $buffer = '';
    open(my $fh, '>', \$buffer) or die "scalar open failed: $!";
    local $\ = "\n";
    ok($fh->write("hello"), 'IO::Handle::write succeeds on scalar-backed filehandles');
    is($buffer, 'hello', 'IO::Handle::write to scalar-backed filehandle ignores output record separator');
}

sub _print_with_local_output_record_separator {
    my ($fh, $data) = @_;
    local $\;
    print $fh $data;
}

{
    my $buffer = '';
    open(my $fh, '>', \$buffer) or die "scalar open failed: $!";
    local $\ = "\n";
    _print_with_local_output_record_separator($fh, "hello");
    is($buffer, 'hello', 'bare local output record separator clears print separator');
}

sub _downgrade_literal_substr {
    my $buffer = \$_[0];
    $buffer = \substr($$buffer, -1, 1);
    return utf8::downgrade($$buffer, 1);
}
ok(_downgrade_literal_substr("xxx\n"), 'utf8::downgrade succeeds on byte-valued substr lvalue of literal argument');

is(0xFFFFFFFF + 1, 4294967296, 'hex integer followed by plus is addition, not a hex exponent');

{
    local $SIG{__DIE__} = sub { die "die hook should be localized away" };
    eval {
        local $SIG{__DIE__};
        die "localized die\n";
    };
    like($@, qr/^localized die/, 'bare local SIG die hook clears existing handler');
}

sub _io_compress_eval_proto_regression ($) { 1 }
{
    my $saw_eval_frame = 0;
    local $SIG{__DIE__} = sub {
        for (my $i = 0; my @caller = caller($i); $i++) {
            $saw_eval_frame = 1 if ($caller[3] || '') eq '(eval)';
        }
    };
    eval q{_io_compress_eval_proto_regression()};
    ok($saw_eval_frame, 'eval-string compile errors expose an eval caller frame to SIG die handlers');
}

my $deflater = Compress::Zlib::deflateInit();
ok(defined $deflater && !defined $deflater->msg, 'Compress::Zlib stream objects provide msg method');

SKIP: {
    require Compress::Raw::Zlib;
    my ($scanner, $scan_status) = eval { Compress::Raw::Zlib::_inflateScanInit() };
    skip "_inflateScanInit unavailable with this Compress::Raw::Zlib API: $@", 2
        if $@ || !defined $scanner;

    ok(defined $scanner->getLastBlockOffset, 'inflateScanStream exposes block offset method');

    my $scan_deflater = eval {
        $scanner->createDeflateStream(
            -AppendOutput => 1,
            -WindowBits   => -Compress::Raw::Zlib::MAX_WBITS(),
            -CRC32        => 1,
        );
    };
    ok(defined $scan_deflater && $scan_deflater->can('deflate'), 'inflateScanStream createDeflateStream accepts option pairs');
}
