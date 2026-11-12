use strict;
use warnings;
use Test::More;

my $counter = 0;
my $formatter = bless {
    custom_placeholder => sub { return ++$counter },
}, 'Issue1377DynamicPlaceholderFormatter';

ok($formatter->format() =~ /^$counter$/,
    'dynamic placeholder result is compared after its callback updates state');
ok($formatter->format() =~ /^$counter$/,
    'dynamic placeholder callback runs again for each formatting call');
ok($formatter->format() =~ /^\d+$/,
    'a literal pattern still matches a numeric callback result');
ok($formatter->format() !~ /^not-a-number$/,
    'a non-matching pattern remains false after a callback');

done_testing;

package Issue1377DynamicPlaceholderFormatter;

sub format {
    return $_[0]->{custom_placeholder}->();
}
