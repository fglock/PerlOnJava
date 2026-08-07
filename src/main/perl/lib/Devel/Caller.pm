package Devel::Caller;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '2.07';
our @EXPORT_OK = qw(caller_cv caller_args caller_vars called_with called_as_method);

sub caller_cv {
    my ($level) = @_;
    return Internals::jperl_caller_cv($level || 0);
}

sub caller_args {
    my ($level) = @_;
    package DB;
    () = caller(($level || 0) + 1);
    return @DB::args;
}

# The complete optree-oriented API is not required by Devel::LexAlias.
sub called_with      { return caller_args(($_[0] || 0) + 1) }
*caller_vars = \&called_with;
sub called_as_method { return }

1;
