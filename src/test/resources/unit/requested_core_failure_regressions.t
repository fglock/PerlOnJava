#!/usr/bin/env perl
use strict;
use warnings;
use B ();

# Exercise the PerlIO loader recursion before Test::More or another module can
# preload Encode.pm or Symbol.pm and hide the @INC hook path.
my $recursive_load_error;
{
    delete $INC{'Symbol.pm'};
    delete $INC{'Encode.pm'};
    my $hook = sub {
        return undef unless caller eq 'main';
        open my $fh, '<:encoding(utf-8)', __FILE__ . '.missing'
            or die "open failed: $!";
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

for my $case ([':-)', qr/in PerlIO layer/],
              [':RequestedMissingLayer', qr/RequestedMissingLayer/]) {
    my ($layer, $expected_warning) = @$case;
    my ($open_ok, $open_errno, $layer_warning);
    {
        use warnings 'layer';
        local $SIG{__WARN__} = sub { $layer_warning .= shift };
        local $! = 0;
        my $layer_fh;
        $open_ok = open($layer_fh, "<$layer", __FILE__);
        $open_errno = 0 + $!;
    }
    ok(!$open_ok, "open rejects invalid PerlIO layer $layer");
    isnt($open_errno, 0, "open with invalid PerlIO layer $layer sets errno");
    like($layer_warning, $expected_warning,
        "open with invalid PerlIO layer $layer warns about the layer");
}

for my $mode ('+>', '+>>') {
    open my $mode_fh, $mode, undef
        or die "anonymous tempfile open failed for $mode: $!";
    ok(defined fileno($mode_fh), "$mode with undef creates an open temporary handle");
    print {$mode_fh} "temporary data";
    seek($mode_fh, 0, 0) or die "seek failed for $mode: $!";
    is(scalar <$mode_fh>, "temporary data", "$mode temporary handle is readable and seekable");
    close $mode_fh;
}

my $missing_type_error;
eval q{my RequestedMissingClass $typed;};
$missing_type_error = $@;
like($missing_type_error, qr/\ANo such class RequestedMissingClass\b/,
    'a typed lexical rejects a package that is not loaded');
eval q{sub { my RequestedMissingClass $typed; }};
like($@, qr/\ANo such class RequestedMissingClass\b/,
    'eval rejects an unknown typed lexical inside an uncalled sub');
{
    package RequestedTypedAliasClass;
    sub marker {}
}
use constant RequestedTypedAlias => 'RequestedTypedAliasClass';
eval q{sub { my RequestedTypedAlias $typed; }};
is($@, '', 'typed lexical class names resolve constant aliases during eval');

our $forward_cv;
{
    package RequestedForwardCVOrigin;
    BEGIN { $main::forward_cv = \&main::requested_forward_cv_target }
}
my $defined_forward_cv = eval q{sub requested_forward_cv_target {}; \&requested_forward_cv_target};
die $@ if $@;
is(B::svref_2object($defined_forward_cv)->STASH->NAME, 'main',
    'defining a forward CV replaces its source package with the definition package');

{
    no warnings 'once';
    my $saved_glob = \*RequestedAnonymousStashDuringDestroy::saved;
    {
        package RequestedAnonymousStashDuringDestroy;
        no strict 'refs';
        no warnings 'once';
        sub DESTROY { eval '++$RequestedAnonymousStashDuringDestroy::during_destroy' }
        ${'RequestedAnonymousStashDuringDestroy::object'} = bless [], __PACKAGE__;
        undef %RequestedAnonymousStashDuringDestroy::;
    }
    is("$$saved_glob", '*__ANON__::saved',
        'a stash stays anonymous while its destructor runs during undef');
}

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

package RequestedResetBlock;
our @beta_array = (1 .. 3);
our %beta_array = (one => 1, two => 2);
sub match_once { 'block-needle' =~ m?block-needle? }

package main;
ok(RequestedResetRegression::match_once(), 'match-once callsite matches initially');
ok(!RequestedResetRegression::match_once(), 'match-once callsite is consumed');
RequestedResetRegression::reset_match_once();
ok(RequestedResetRegression::match_once(), 'reset re-enables the caller package match-once callsite');
RequestedResetRegression::reset_alpha();
ok(!defined($RequestedResetRegression::alpha), 'reset uses the caller package for named globals');

{
    no strict 'refs';
    no warnings 'once';
    *RequestedResetBlock::beta_array = \1;
}
package RequestedResetBlock { reset 'b' }
is(scalar @RequestedResetBlock::beta_array, 0,
    'reset in a package block clears that package array in the interpreter');
is(scalar keys %RequestedResetBlock::beta_array, 0,
    'reset in a package block clears that package hash in the interpreter');
is($RequestedResetBlock::beta_array, 1,
    'reset preserves a readonly scalar sharing the reset array/hash glob');

ok(RequestedResetBlock::match_once(), 'package-block match-once callsite matches initially');
ok(!RequestedResetBlock::match_once(), 'package-block match-once callsite is consumed');
package RequestedResetBlock { reset }
ok(RequestedResetBlock::match_once(),
    'reset in a package block re-enables that package regex callsite');

my $reset_warning_switch;
{
    local $SIG{__WARN__} = sub {};
    local $^W = 1;
    reset "\cW";
    $reset_warning_switch = $^W;
}
is($reset_warning_switch, 0, 'reset of \cW leaves the numeric warning switch at zero');

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
