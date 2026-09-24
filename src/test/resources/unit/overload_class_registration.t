use strict;
use warnings;
use Test::More;

plan skip_all => 'method-valued class overloads require Perl 5.44'
    if $^V lt v5.44.0;

my @warnings;
my $result;
my $error;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    $result = eval q{
        use v5.40;
        use experimental 'class';
        class OverloadRegistration {
            field $value :param;
            use overload '""' => method ($, $) { $value }, fallback => 1;
        }
        "" . OverloadRegistration->new(value => 'registered');
    };
    $error = $@;
}

is($error, '', 'class-local overload registration compiles');
is($result, 'registered', 'class-local overload is installed');
is_deeply(\@warnings, [], 'class-local overload emits no redefinition warnings');

done_testing;
