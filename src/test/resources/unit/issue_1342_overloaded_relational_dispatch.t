use strict;
use warnings;
use Test::More;

{
    package Local::PartialOrderKey;

    use overload
        'eq' => sub {
            @_ = upgrade(@_);
            my ($left, $right) = @_;
            $$left eq $$right;
        },
        'lt' => \&less,
        'le' => sub {
            @_ = upgrade(@_);
            my ($left, $right) = @_;
            $left lt $right || $left eq $right;
        },
        'gt' => sub { @_ = upgrade(@_); $_[1] lt $_[0] },
        'ge' => sub {
            @_ = upgrade(@_);
            my ($left, $right) = @_;
            $right lt $left || $left eq $right;
        },
        '""' => sub { ${$_[0]} };

    sub less ($$) {
        @_ = upgrade(@_);
        my ($left, $right) = @_;
        my ($a, $b) = ("$left", "$right");
        length($a) < length($b) && substr($b, 0, length($a) + 1) eq "$a/";
    }

    sub new {
        my ($class, $key) = @_;
        $key =~ s{/+}{/}g;
        $key =~ s{^/*}{/};
        $key =~ s{/+$}{};
        return bless \$key, $class;
    }

    sub upgrade {
        map { ref $_ ? $_ : __PACKAGE__->new($_) } @_;
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
