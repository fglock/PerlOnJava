use strict;
use warnings;
use Test::More;

{
    package Local::UniqueMutableNumber;

    use overload
        '+=' => sub {
            $_[0]{value} += $_[1];
            return $_[0];
        },
        '=' => sub {
            $main::unique_copy_calls++;
            # Deliberately copy only the class's core field. An unnecessary
            # copy would discard subclass state, as Math::BigInt::copy does.
            return bless { value => $_[0]{value} }, ref $_[0];
        };
}

our $unique_copy_calls = 0;
my $number = bless {
    value  => 23,
    custom => 'preserved',
}, 'Local::UniqueMutableNumber';

$number += 23;

is($number->{value}, 46, 'mutating overload updates an unshared object');
is($number->{custom}, 'preserved', 'unshared mutation preserves subclass fields');
is($unique_copy_calls, 0, 'copy constructor is skipped for an unshared object');

done_testing;
