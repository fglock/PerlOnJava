use strict;
use warnings;
use Test::More;

my @seen;

sub callback_for {
    my ($label) = @_;
    return qr/(?{ push @seen, $label })/;
}

my $a = callback_for('A');
my $b = callback_for('B');

ok('' =~ $a, 'first identical-source callback regex matches');
ok('' =~ $b, 'second identical-source callback regex matches');
ok('' =~ $a, 'first callback regex remains reusable');
is_deeply(\@seen, [qw(A B A)],
    'compiled regex cache never retains another regex callback table');

@seen = ();
my $prefix = '(?:)';
my $wrapped_a = qr/$prefix$a/;
my $wrapped_b = qr/$prefix$b/;
ok('' =~ $wrapped_a && '' =~ $wrapped_b && '' =~ $wrapped_a,
    'identical runtime templates remain reusable');
is_deeply(\@seen, [qw(A B A)],
    'runtime template recompilation keeps each callback closure table');

done_testing;
