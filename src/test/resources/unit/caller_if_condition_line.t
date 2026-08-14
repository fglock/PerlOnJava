use strict;
use warnings;
no warnings 'syntax';
use Test::More tests => 2;

my @caller_lines;
sub capture_caller_line { push @caller_lines, (caller)[2] }

my $ordinary_call_line = __LINE__ + 2;
if (1) {
    capture_caller_line();
}
is($caller_lines[0], $ordinary_call_line,
    'ordinary if body call keeps its own source line');

my $declaration_condition_line = __LINE__ + 1;
if (my $enabled = 1) {
    capture_caller_line();
}
is($caller_lines[1], $declaration_condition_line,
    'lexical declaration condition owns the first call source line');
