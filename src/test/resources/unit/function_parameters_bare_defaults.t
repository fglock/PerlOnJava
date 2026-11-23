use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    use Function::Parameters qw(:strict);
    fun bare_default($value =) { [$value] }
    fun bare_defaults($first=, $second =) { [$first, $second] }
    fun mixed_defaults($first = 'default', $second= ) { [$first, $second] }
    1;
};
if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
    plan skip_all => 'Function::Parameters is not installed in system Perl';
}
die $@ unless $loaded;

is_deeply(bare_default(), [undef], 'an empty default expression means undef');
is_deeply(bare_default('value'), ['value'], 'an explicit argument overrides an empty default');
is_deeply(bare_defaults(), [undef, undef], 'adjacent empty defaults both mean undef');
is_deeply(bare_defaults('first'), ['first', undef], 'later empty defaults apply independently');
is_deeply(mixed_defaults(), ['default', undef], 'empty and explicit defaults can be mixed');
is_deeply(mixed_defaults('first', 'second'), ['first', 'second'],
    'arguments override both explicit and empty defaults');

done_testing;
