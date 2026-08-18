use strict;
use warnings;
use utf8;
use Config;
use Test::More;

plan skip_all => 'requires a 64-bit Perl UV' if $Config{uvsize} < 8;

sub compile_escape {
    my ($escape, $in_class, $upgraded_source, $strict) = @_;
    my $source = $in_class ? "[$escape]" : $escape;
    utf8::upgrade($source) if $upgraded_source;

    my (@warnings, $rx, $error);
    {
        no warnings qw(non_unicode portable utf8);
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        if ($strict) {
            no warnings 'experimental::re_strict';
            use re 'strict';
            $rx = eval { qr/$source/ };
        }
        else {
            $rx = eval { qr/$source/ };
        }
        $error = $@;
    }
    return ($rx, $error, join('', @warnings));
}

sub matches_escape {
    my ($subject, @compile_args) = @_;
    my ($rx, $error) = compile_escape(@compile_args);
    return defined($rx) && !$error && $subject =~ $rx;
}

{
    no warnings qw(non_unicode portable utf8);
    my $above_unicode = chr(0x110000);
    for my $case (
        [ '\x{11_0000}', 0, 0, 'hex literal from byte pattern source' ],
        [ '\x{11_0000}', 1, 0, 'hex class from byte pattern source' ],
        [ '\o{4_200_000}', 0, 0, 'octal literal from byte pattern source' ],
        [ '\o{4_200_000}', 1, 0, 'octal class from byte pattern source' ],
        [ '\x{11_0000}', 0, 1, 'hex literal from Unicode pattern source' ],
        [ '\x{11_0000}', 1, 1, 'hex class from Unicode pattern source' ],
        [ '\o{4_200_000}', 0, 1, 'octal literal from Unicode pattern source' ],
        [ '\o{4_200_000}', 1, 1, 'octal class from Unicode pattern source' ],
    ) {
        my ($escape, $class, $upgraded, $name) = @$case;
        ok(matches_escape($above_unicode, $escape, $class, $upgraded, 0), $name);
    }

    for my $case (
        [ '\x{11_0000}', 0, 'strict hex literal above Unicode' ],
        [ '\x{11_0000}', 1, 'strict hex class above Unicode' ],
        [ '\o{4_200_000}', 0, 'strict octal literal above Unicode' ],
        [ '\o{4_200_000}', 1, 'strict octal class above Unicode' ],
    ) {
        my ($escape, $class, $name) = @$case;
        ok(matches_escape($above_unicode, $escape, $class, 0, 1), $name);
    }

    my $signed_iv_max = chr(hex('7FFFFFFFFFFFFFFF'));
    for my $case (
        [ '\x{7FFF_FFFF_FFFF_FFFF}', 0, 'hex literal at signed IV max' ],
        [ '\x{7FFF_FFFF_FFFF_FFFF}', 1, 'hex class at signed IV max' ],
        [ '\o{777_777_777_777_777_777_777}', 0, 'octal literal at signed IV max' ],
        [ '\o{777_777_777_777_777_777_777}', 1, 'octal class at signed IV max' ],
    ) {
        my ($escape, $class, $name) = @$case;
        ok(matches_escape($signed_iv_max, $escape, $class, 0, 0), $name);
    }

    my $above_unicode_next = chr(0x110001);
    my $unicode_max = chr(0x10FFFF);
    ok($above_unicode =~ /\A[^\x{11_0001}]\z/,
        'negated wide singleton accepts another wide scalar');
    ok($above_unicode !~ /\A[^\x{11_0000}]\z/,
        'negated wide singleton excludes itself');
    ok($above_unicode =~ /\A[\x{11_0000}-\x{11_0001}]\z/,
        'wide range includes lower endpoint');
    ok($above_unicode_next =~ /\A[\x{11_0000}-\x{11_0001}]\z/,
        'wide range includes upper endpoint');
    ok($signed_iv_max =~ /\A[\x{11_0000}-\x{7FFF_FFFF_FFFF_FFFF}]\z/,
        'wide range reaches signed IV max');
    ok($unicode_max =~ /\A[\x{10FFFF}-\x{11_0000}]\z/,
        'cross-boundary range includes Unicode endpoint');
    ok($above_unicode =~ /\A[\x{10FFFF}-\x{11_0000}]\z/,
        'cross-boundary range includes wide endpoint');
    ok($above_unicode_next !~ /\A[\x{10FFFF}-\x{11_0000}]\z/,
        'cross-boundary range excludes successor');
    ok($above_unicode =~ /\A[^\x{10FFFF}-\x{10FFFF}]\z/,
        'ordinary negated class consumes a wide scalar once');

    for my $case (
        [ '\x{11_0000_}', 'default hex truncates at a trailing underscore' ],
        [ '\o{4_200_0008}', 'default octal truncates at an invalid digit' ],
    ) {
        my ($escape, $name) = @$case;
        my ($rx, $error) = compile_escape($escape, 0, 0, 0);
        ok($rx && !$error && $above_unicode =~ $rx, "$name and preserves the prefix value");
    }
}

for my $case (
    [ '\x{8000_0000_0000_0000}', 0, 'hex literal above signed IV max' ],
    [ '\x{8000_0000_0000_0000}', 1, 'hex class above signed IV max' ],
    [ '\o{1_000_000_000_000_000_000_000}', 0, 'octal literal above signed IV max' ],
    [ '\o{1_000_000_000_000_000_000_000}', 1, 'octal class above signed IV max' ],
) {
    my ($escape, $class, $name) = @$case;
    my ($rx, $error) = compile_escape($escape, $class, 0, 0);
    ok(!$rx && $error =~ /permissible max is 0x7FFFFFFFFFFFFFFF/, "$name is fatal");
}

done_testing;
