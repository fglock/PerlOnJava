use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our @destroyed;
{ package ReturnedCellChild; sub DESTROY { push @main::destroyed, $_[0]{name} } }
sub implicit_cell {
    my ($name) = @_;
    \(my $value = bless { name => $name }, 'ReturnedCellChild');
}
sub explicit_cell {
    my ($name) = @_;
    my $value = bless { name => $name }, 'ReturnedCellChild';
    return \$value;
}
for my $factory (\&implicit_cell, \&explicit_cell) {
    my $name = $factory == \&implicit_cell ? 'implicit' : 'explicit';
    my $cell = $factory->($name);
    my $weak = $$cell;
    weaken($weak);
    is($$cell->{name}, $name, "$name return preserves the cell's contents");
    ok(defined $weak, "$name return owns its contents after lexical exit");
    $$cell = undef;
    ok(!defined $weak, "$name return releases contents on assignment through the cell");
    is_deeply(\@destroyed, $name eq 'implicit' ? ['implicit'] : ['implicit', 'explicit'],
        "$name cell contents are destroyed exactly once");
}
done_testing();
