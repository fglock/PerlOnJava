#!/usr/bin/env perl
use strict;
use warnings;

# Exercise the PerlIO loader recursion before Test::More or another module can
# preload Encode.pm or Symbol.pm and hide the @INC hook path.
my $recursive_load_error;
{
    delete $INC{'Symbol.pm'};
    delete $INC{'Encode.pm'};
    my $hook = sub {
        return undef unless caller eq 'main';
        open my $fh, '<:encoding(utf-8)', __FILE__ or die "open failed: $!";
        return $fh;
    };
    unshift @INC, $hook;
    eval { require Symbol; 1 };
    $recursive_load_error = $@;
    shift @INC;
    delete $INC{'Symbol.pm'};
    delete $INC{'Encode.pm'};
}

use Test::More;
use File::Temp qw(tempfile);

like($recursive_load_error,
    qr/\ARecursive call to Perl_load_module in PerlIO_find_layer at/s,
    'recursive module loading through an encoding layer is diagnosed');

my $wide_latin1 = "\x{00B5}";
utf8::upgrade($wide_latin1);
my @wide_latin1_bytes;
{
    use bytes;
    @wide_latin1_bytes = unpack('C*', $wide_latin1);
}
is_deeply(\@wide_latin1_bytes, [0xC2, 0xB5],
    'unpack C under use bytes reads UTF-8 storage bytes for an upgraded Latin-1 scalar');

my ($temp_fh, $temp_path) = tempfile();
print {$temp_fh} "first\r\nsecond\r\n";
close $temp_fh or die "close failed: $!";
open my $crlf_fh, '<:crlf', $temp_path or die "open failed: $!";
is(scalar <$crlf_fh>, "first\n", ':crlf normalizes the first line');
is(scalar <$crlf_fh>, "second\n", ':crlf normalizes the final line');
ok(eof($crlf_fh), ':crlf consumes a final CRLF pair before reporting EOF');
close $crlf_fh;
unlink $temp_path;

open my $anonymous_fh, '+<', undef or die "anonymous tempfile open failed: $!";
ok(defined fileno($anonymous_fh), 'three-argument open with undef creates an open temporary handle');
print {$anonymous_fh} "temporary data";
seek($anonymous_fh, 0, 0) or die "seek failed: $!";
is(scalar <$anonymous_fh>, "temporary data", 'anonymous temporary handle is readable and seekable');
close $anonymous_fh;

my $directory_errno;
{
    local $! = 0;
    opendir my $directory_fh, '.' or die "opendir failed: $!";
    my $directory_fileno = fileno($directory_fh);
    $directory_errno = 0 + $!;
    closedir $directory_fh or die "closedir failed: $!";
    ok(defined($directory_fileno) || $directory_errno != 0,
        'directory fileno either returns a descriptor or sets errno');
}

my $unsigned_not = ~0;
my $signed_not = do { use integer; ~0 };
is($signed_not, -1, 'use integer makes bitwise complement return a signed IV');
my $cusp = 1 << 63;
my $signed_and = do { use integer; $cusp & -1 };
ok($unsigned_not > 0 && $signed_and < 0,
    'use integer applies signed semantics to bitwise AND');

package RequestedResetRegression;
our $alpha = 'defined';
sub reset_alpha { reset 'a' }
sub match_once { 'needle' =~ m?needle? }
sub reset_match_once { reset }

package main;
ok(RequestedResetRegression::match_once(), 'match-once callsite matches initially');
ok(!RequestedResetRegression::match_once(), 'match-once callsite is consumed');
RequestedResetRegression::reset_match_once();
ok(RequestedResetRegression::match_once(), 'reset re-enables the caller package match-once callsite');
RequestedResetRegression::reset_alpha();
ok(!defined($RequestedResetRegression::alpha), 'reset uses the caller package for named globals');

my @warnings;
{
    local $^W = 1;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    my $undefined;
    my $string = 'x';
    my $result = $undefined | $string;
    $result = $string | $undefined;
    $result = $undefined & $string;
    $result = $string & $undefined;
    $result = $undefined ^ $string;
    $result = $string ^ $undefined;
    ($result = $undefined) |= $string;
    ($result = $undefined) &= $string;
    ($result = $undefined) ^= $string;
    ($result = $string) |= $undefined;
    ($result = $string) &= $undefined;
    ($result = $string) ^= $undefined;
}
is(scalar @warnings, 10, 'bitwise operations warn once per uninitialized operand with Perl assignment exceptions');

done_testing();
