use strict;
use warnings;
use utf8;
use Test::More;

my $program = <<'PERL';
use Encode ();
my $decoded = Encode::decode('UTF-8', $ARGV[0]);
print join ',', map { ord } split //, $decoded;
PERL

my $argument = "Υ";
open my $child, '-|', $^X, '-e', $program, $argument
    or die "cannot start child Perl: $!";
my $output = do { local $/; <$child> };
my $success = close $child;

ok($success, 'child decodes a UTF-8 command-line argument');
is($output, '933', 'argv contains UTF-8 octets for Encode::decode');

open my $unicode_child, '-|', $^X, '-CA', '-e', 'print ord shift', $argument
    or die "cannot start child Perl with -CA: $!";
my $unicode_output = do { local $/; <$unicode_child> };
my $unicode_success = close $unicode_child;

ok($unicode_success, 'child accepts a Unicode command-line argument with -CA');
is($unicode_output, '933', '-CA exposes command-line arguments as decoded Unicode');

done_testing;
