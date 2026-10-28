use strict;
use warnings;
use Test::More;

{
    package Local::PartialOrderKey;

    use overload
        'eq' => sub {
            my ($left, $right) = map { ref $_ ? $_ : __PACKAGE__->new($_) } @_;
            $$left eq $$right;
        },
        'lt' => sub {
            my ($left, $right) = map { ref $_ ? $_ : __PACKAGE__->new($_) } @_;
            my ($a, $b) = ("$left", "$right");
            length($a) < length($b) && substr($b, 0, length($a) + 1) eq "$a/";
        },
        'le' => sub {
            my ($left, $right) = map { ref $_ ? $_ : __PACKAGE__->new($_) } @_;
            $left lt $right || $left eq $right;
        },
        'gt' => sub { $_[1] lt $_[0] },
        'ge' => sub {
            my ($left, $right) = map { ref $_ ? $_ : __PACKAGE__->new($_) } @_;
            $right lt $left || $left eq $right;
        },
        '""' => sub { ${$_[0]} };

    sub new {
        my ($class, $key) = @_;
        $key =~ s{/+}{/}g;
        $key =~ s{^/*}{/};
        $key =~ s{/+$}{};
        return bless \$key, $class;
    }
}

sub compare_dynamically {
    my ($left, $right) = @_;
    return $left le $right;
}

my $parent = Local::PartialOrderKey->new('/foo');
my $child = Local::PartialOrderKey->new('/foo/bar');
my $sibling = Local::PartialOrderKey->new('/other');

ok(compare_dynamically($parent, $child),
    'relational overloads dispatch through dynamically typed subroutine arguments');
ok(compare_dynamically($parent, '/foo'),
    'relational overloads upgrade a string operand inside an overload method');
ok(!($parent eq $sibling), 'key equality remains independent of ordering');

done_testing;
