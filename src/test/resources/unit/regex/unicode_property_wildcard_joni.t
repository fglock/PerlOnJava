use strict;
use warnings;
use utf8;
use Test::More;

no warnings 'experimental::uniprop_wildcards';

sub compile_property {
    my ($property) = @_;
    my $pattern = eval 'qr!\A\p{' . $property . '}\z!u';
    return ($pattern, $@);
}

my ($block_search, $block_error) = compile_property('Block=#Basic#');
ok(defined $block_search, 'unanchored Block wildcard compiles')
    or diag($block_error);
like('A', $block_search, 'unanchored Block wildcard uses search semantics');
unlike(chr(0x0378), $block_search,
    'unanchored Block wildcard excludes other blocks');

my ($block_anchored, $block_anchored_error) =
    compile_property('Block=#\ABasic\z#');
ok(!defined($block_anchored), 'anchored Block wildcard remains a full match');
ok(length($block_anchored_error),
    'unmatched anchored Block wildcard reports a compile error');

my ($script, $script_error) =
    compile_property('Script=:\A(?:Lat(in)?|Grek)\z:');
ok(defined $script, 'anchored Script wildcard compiles') or diag($script_error);
like('A', $script, 'anchored Script wildcard selects Latin');
like(chr(0x03B1), $script, 'anchored Script wildcard selects Greek');
unlike(chr(0x0410), $script, 'anchored Script wildcard excludes Cyrillic');

my ($name, $name_error) = compile_property('name=/KATAKANA/');
ok(defined $name, 'case-sensitive name wildcard compiles') or diag($name_error);
like(chr(0x30CD), $name, 'name wildcard selects KATAKANA LETTER NE');
unlike('A', $name, 'name wildcard excludes unrelated character names');

my ($lower_name, $lower_name_error) = compile_property('name=/katakana/');
ok(!defined($lower_name), 'name wildcard is case-sensitive by default');
ok(length($lower_name_error),
    'case-sensitive name miss reports a compile error');

my ($folded_name, $folded_name_error) =
    compile_property('name=/(?i:katakana)/');
ok(defined $folded_name, 'name wildcard accepts explicit case folding')
    or diag($folded_name_error);
like(chr(0x30CD), $folded_name,
    'explicitly folded name wildcard selects KATAKANA LETTER NE');

done_testing;
