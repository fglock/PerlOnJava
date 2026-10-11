#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# #1625: heredoc content belongs to eval while the postfix modifier applies
# to the eval statement.
eval <<'END_PERL' unless defined &issue_1625_defined_sub;
sub issue_1625_defined_sub { 1 }
END_PERL
is($@, '', 'postfix unless parses after an eval heredoc');
is(issue_1625_defined_sub(), 1, 'eval heredoc defines the subroutine');

eval <<'END_PERL' unless defined &issue_1625_no_semicolon;
sub issue_1625_no_semicolon { 2 }
END_PERL
is($@, '', 'postfix unless parses before a heredoc without a semicolon');
is(issue_1625_no_semicolon(), 2, 'semicolon-free eval heredoc defines the subroutine');

# #1640: imported block and attribute functions can be nested in a Test::More
# argument list. These local stand-ins keep the parser regression independent
# of Mouse and Test::Fatal installations.
our @issue_1640_has_args;
sub exception (&) {
    $_[0]->();
    return undef;
}
sub has {
    @issue_1640_has_args = @_;
    return;
}

is exception {
    has foo => (
        traits  => [qw(Array)],
        default => sub { [] },
        handles => { mypush0 => 'push' },
    );
}, undef, 'exception block accepts an imported has call';
is($issue_1640_has_args[0], 'foo', 'has receives the attribute name');
my %issue_1640_options = @issue_1640_has_args[1 .. $#issue_1640_has_args];
is_deeply($issue_1640_options{traits}, ['Array'], 'has receives the trait option');
is($issue_1640_options{handles}{mypush0}, 'push', 'has receives the delegated method');
is(ref($issue_1640_options{default}), 'CODE', 'attribute default remains a callback');

# #1663: qq may use an apostrophe delimiter as a bareword-call argument.
use Carp;
my $issue_1663_value = \1;
if (ref $issue_1663_value eq 'SCALAR') {
    local $^W;
    croak qq'"$$issue_1663_value" is not numeric'
        if $$issue_1663_value ne (0 + $$issue_1663_value);
}
pass('qq apostrophe delimiter parses in a bareword call argument');

done_testing;
