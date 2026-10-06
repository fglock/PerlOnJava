use strict;
use warnings;
use Test::More;
use Carp ();

if (!eval { require Moo; 1 }) {
    plan skip_all => 'Moo is required for the destructor error-location regression';
}
require Moo::sification;
Moo::sification->unimport;

my $package = 'CarpMooDemolishLocation';
our $demolish_caller_package;
my $code = <<'MOO_CODE';
use Carp qw(croak);
use Moo;
sub DEMOLISH {
    my @caller = caller(3);
    $main::demolish_caller_package = $caller[0]
        unless defined $main::demolish_caller_package;
    croak "demolish" unless $_[0]->{demolished}++;
}
my $obj = CarpMooDemolishLocation->new;
package Elsewhere;
$obj->DESTROY;
MOO_CODE

my $source_before_failure = $code;
$source_before_failure =~ s/\n\z//;
my $expected_line = 1 + ($source_before_failure =~ tr/\n//);
my $test_sub = eval(
    "sub {\n"
        . "package $package;\n"
        . "#line 1 MooDemolishLocationTest\n"
        . $code
        . "\n}\n"
);
BAIL_OUT("could not compile Moo destructor reproducer: $@") unless $test_sub;

my ($full_trace, $last_location, $immediate);
my $error;
{
    local $@;
    eval {
        local $Carp::Verbose = 0;
        local $SIG{__WARN__};
        local $SIG{__DIE__} = sub {
            my @caller = caller;
            my ($location) = $_[0] =~ /^.* at (.*? line \d+)\.?\n?\z/s;
            return if !$location || ($last_location && $last_location eq $location);
            $last_location = $location;
            $immediate = $caller[1] eq 'MooDemolishLocationTest';
            {
                local %Carp::Internal;
                local %Carp::CarpInternal;
                $full_trace = Carp::longmess('');
            }
        };
        $test_sub->();
        1;
    };
    $error = $@;
}

my ($location) = $error =~ /.* at (.*? line \d+)\.?\n?\z/s;
isnt($immediate, 1, 'the user croak passes through Moo destructor handling');
is($demolish_caller_package, 'Elsewhere', 'DESTROY call retains its caller package');
is(
    $location,
    "MooDemolishLocationTest line $expected_line",
    'croak rethrown by Moo DESTROY keeps the DEMOLISH call site',
) or diag("error: $error\nfull trace: $full_trace");

done_testing();
