use strict;
use warnings;
use Test::More;

sub warnings_for {
    my ($source) = @_;
    my $warnings = '';
    local $SIG{__WARN__} = sub { $warnings .= join '', @_ };
    eval $source;
    return $warnings;
}

my $after_at = warnings_for 'sub warning_after_at (@ $b ar) { }';
like($after_at, qr/Prototype after '\@'/, 'prototype arguments after @ are diagnosed');

my $after_underscore = warnings_for 'sub warning_after_underscore ($_$) { }';
like($after_underscore, qr/Illegal character after '_'/, 'invalid underscore placement is diagnosed');

my $missing_bracket = warnings_for 'sub warning_missing_bracket ([) { }';
like($missing_bracket, qr/Missing '\]'/, 'unmatched prototype bracket is diagnosed');

done_testing;
