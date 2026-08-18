use strict;
use warnings;
use Test::More tests => 11;

my $smile = chr(0x1F642);
my $subject = ('A' . chr(0xE9) . $smile) x 2000;
my ($matches, $last_pos) = (0);
while ($subject =~ /./g) {
    $matches++;
    $last_pos = pos($subject);
}
is($matches, 6000, 'global match visits every Perl character');
is($last_pos, 6000, 'successful global pos is in Perl characters');
is(pos($subject), undef, 'terminal failed global match resets pos');

my $short = 'A' . $smile . 'B';
pos($short) = 2;
ok($short =~ /\GB/g, 'global match converts pos beyond supplementary character');
is(pos($short), 3, 'global match publishes logical end position');

pos($short) = 2;
ok($short =~ /\GB/, 'non-global G assertion converts assigned pos');
is(pos($short), 2, 'successful non-global G assertion preserves pos');

my $replaced = 'A' . $smile . 'B';
my $replacement_pos;
pos($replaced) = 2;
is($replaced =~ s/\GB/do { $replacement_pos = pos($replaced); 'X' }/e, 1,
    'substitution G assertion converts logical pos');
is($replacement_pos, 2, 'replacement code observes logical match start');
is($replaced, 'A' . $smile . 'X', 'substitution starts after supplementary character');
is(pos($replaced), undef, 'destructive substitution invalidates pos');
