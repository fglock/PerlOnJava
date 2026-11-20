#!/usr/bin/perl

use strict;
use warnings;
use Test::More;
use Text::Unicode::Equivalents qw( all_strings);
use Encode;

# Test Data
#
# Each subarray contains a closed set of canonically equivalent strings.
# Within any of these sets, passing any element to all_strings() should
# generate the entire set.


my @tests = (
	# Some Latin examples:
	[
		"a\x{0306}\x{0303}\x{0323}",
		"a\x{0306}\x{0323}\x{0303}",
		"a\x{0323}\x{0306}\x{0303}",
		"\x{0103}\x{0303}\x{0323}",
		"\x{0103}\x{0323}\x{0303}",
		"\x{1ea1}\x{0306}\x{0303}",
		"\x{1eb5}\x{0323}",
		"\x{1eb7}\x{0303}"
	],
	
	# Verify Singleton and non-starting compositions:
	[
		"\x{00eb}\x{0301}A\x{030a}",
		"\x{00eb}\x{0301}\x{00c5}",
		"\x{00eb}\x{0301}\x{212b}",
		"\x{00eb}\x{0341}A\x{030a}",
		"\x{00eb}\x{0341}\x{00c5}",
		"\x{00eb}\x{0341}\x{212b}",
		"e\x{0308}\x{0301}A\x{030a}",
		"e\x{0308}\x{0301}\x{00c5}",
		"e\x{0308}\x{0301}\x{212b}",
		"e\x{0308}\x{0341}A\x{030a}",
		"e\x{0308}\x{0341}\x{00c5}",
		"e\x{0308}\x{0341}\x{212b}",
		"e\x{0344}A\x{030a}",
		"e\x{0344}\x{00c5}",
		"e\x{0344}\x{212b}"
	],
	
	# Hangul composition test:
	[
 		"\x{1100}\x{1161}\x{11a8}",
		"\x{ac00}\x{11a8}",
		"\x{ac01}"
	]
	
);



# count up the tests:
my $c;
map { $c += scalar(@{$_})  } @tests;
plan tests => $c;

# execute tests
for my $test (@tests) {
	
	# Make sure test data is sorted
	$test = [ sort @{$test} ];
	
	foreach my $src (@{$test}) 	{
		SKIP: {
			skip "Hangul Jamo test fails on Perl 5.10 or earlier", 1 if $src =~ /[\x{1100}-\x{11FF}]/ && $] < 5.012;
			my $res = [ sort @{all_strings($src)} ];
			ok(_compare_arrays($test, $res), 't/tests "' . encode('ascii', $src, Encode::FB_PERLQQ) . '"');
		}
	}
}

sub _compare_arrays {
    my ($first, $second) = @_;
#    no warnings;  # silence spurious -w undef complaints
    return 0 unless @$first == @$second;
    for (my $i = 0; $i < @$first; $i++) {
            return 0 if $first->[$i] ne $second->[$i];
            }
    return 1;
}


