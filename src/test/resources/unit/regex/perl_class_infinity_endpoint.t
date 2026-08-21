use strict;
use warnings;
use Config;
use Test::More;
use Unicode::UCD;

plan skip_all => 'requires a 64-bit Perl UV' if $Config{uvsize} < 8;

my $highest_hex = sprintf '%X', $Unicode::UCD::MAX_CP;
my $infinity_hex = $highest_hex;
$infinity_hex =~ s/^7/F/;

is($highest_hex, '7FFFFFFFFFFFFFFF',
   'HIGHEST_CP is the signed 64-bit scalar ceiling');
is($infinity_hex, 'FFFFFFFFFFFFFFFF',
   'INFTY is the symbolic unsigned 64-bit class ceiling');

sub compile_pattern {
    my ($source) = @_;
    my ($regex, $error);
    {
        no warnings qw(non_unicode portable utf8);
        $regex = eval "qr/\\A(?:$source)\\z/";
        $error = $@;
    }
    return ($regex, $error);
}

sub compiles {
    my ($source, $name) = @_;
    my ($regex, $error) = compile_pattern($source);
    ok(defined($regex) && !$error, $name);
    return $regex;
}

sub is_fatal {
    my ($source, $name) = @_;
    my ($regex, $error) = compile_pattern($source);
    ok(!defined($regex) && $error =~ /permissible max/, $name);
}

my $hex_rhs = compiles("[\\x{0}-\\x{$infinity_hex}]",
    'hex UV_MAX is accepted as a class range RHS');
my $octal_infinity = '1' . ('7' x 21);
my $octal_rhs = compiles("[\\o{0}-\\o{$octal_infinity}]",
    'octal UV_MAX is accepted as a class range RHS');

is_fatal("\\x{$infinity_hex}",
    'UV_MAX is fatal as an executable regex literal');
is_fatal("[\\x{$infinity_hex}]",
    'UV_MAX is fatal as a singleton class member');
is_fatal("[\\x{$infinity_hex}-\\x{$infinity_hex}]",
    'UV_MAX is fatal as a class range LHS');
is_fatal('[\\x{0}-\\x{8000000000000000}]',
    'the first intermediate unsigned value is not INFTY');
is_fatal('[\\x{0}-\\x{FFFFFFFFFFFFFFFE}]',
    'UV_MAX minus one is not INFTY');
is_fatal('[\\x{0}-\\x{10000000000000000}]',
    'a value wider than a Perl UV is fatal');

{
    no warnings qw(non_unicode portable utf8);
    my $character = eval { chr(hex($infinity_hex)) };
    ok(!defined($character) && $@ =~ /permissible max/,
       'INFTY cannot be constructed as an executable subject character');
}

my $highest_singleton = compiles("[\\x{$highest_hex}]",
    'HIGHEST_CP remains a legal singleton unlike INFTY');
my $highest_range = compiles("[\\x{101}-\\x{$highest_hex}]",
    'HIGHEST_CP remains a finite signed-domain range endpoint');
my $infinity_range = compiles("[\\x{101}-\\x{$infinity_hex}]",
    'INFTY remains a distinct symbolic range endpoint');

{
    no warnings qw(non_unicode portable utf8);
    my $highest_character = chr(hex($highest_hex));
    ok($highest_character =~ $highest_singleton,
       'HIGHEST_CP singleton matches the signed-domain ceiling');
    for my $subject (chr(0x101), chr(0x110000), $highest_character) {
        ok(defined($infinity_range)
           && $subject =~ $highest_range && $subject =~ $infinity_range,
           'HIGHEST_CP and INFTY ranges agree for every legal sampled subject');
    }
}

my @anyofh_representatives = (
    [ "[\\x{101}-\\x{$infinity_hex}]",
      [ 0x101, 0x110000 ], [ 0x100 ],
      'ANYOFH single open-high range' ],
    [ "[\\x{102}-\\x{104}\\x{106}-\\x{$infinity_hex}]",
      [ 0x102, 0x104, 0x106, 0x110000 ], [ 0x101, 0x105 ],
      'ANYOFH preserves a finite gap before INFTY' ],
    [ "[\\x{10C}-\\x{$infinity_hex}\\x{102}-\\x{104}"
        . "\\x{108}-\\x{10A}\\x{103}-\\x{109}]",
      [ 0x102, 0x109, 0x10C, 0x110000 ], [ 0x101, 0x10B ],
      'ANYOFH coalesces overlapping finite ranges below INFTY' ],
    [ "[\\x{106}-\\x{$infinity_hex}\\x{104}-\\x{$highest_hex}]",
      [ 0x104, 0x105, 0x106, 0x110000 ], [ 0x103 ],
      'ANYOFH retains INFTY when merged with HIGHEST_CP' ],
);

{
    no warnings qw(non_unicode portable utf8);
    for my $case (@anyofh_representatives) {
        my ($source, $members, $nonmembers, $name) = @$case;
        my $regex = compiles($source, "$name compiles");
        ok(defined($regex) && !grep({ chr($_) !~ $regex } @$members),
           "$name includes representative members");
        ok(defined($regex) && !grep({ chr($_) =~ $regex } @$nonmembers),
           "$name excludes representative gaps");
    }
}

my @full_boundaries = (
    [ "[\\x{00}-\\x{$infinity_hex}]",
      'SANY boundary 936: 00-INFTY' ],
    [ "[\\x{10C}-\\x{$infinity_hex}\\x{00}-\\x{$highest_hex}]",
      'SANY boundary 1091: high-INFTY plus 00-HIGHEST_CP' ],
    [ "[\\x{10C}-\\x{$infinity_hex}\\x{00}-\\x{$infinity_hex}]",
      'SANY boundary 1093: high-INFTY plus 00-INFTY' ],
);

{
    no warnings qw(non_unicode portable utf8);
    my @legal_subjects = (chr(0), 'A', chr(0x100), chr(0x110000),
                          chr(hex($highest_hex)));
    for my $case (@full_boundaries) {
        my ($source, $name) = @$case;
        my $regex = compiles($source, "$name compiles");
        ok(defined($regex) && !grep({ $_ !~ $regex } @legal_subjects),
           "$name includes the full legal scalar-domain sample");
    }
}

ok(defined($hex_rhs) && defined($octal_rhs)
   && "A" =~ $hex_rhs && "A" =~ $octal_rhs,
   'hex and octal INFTY endpoints have identical legal matching behavior');

done_testing;
