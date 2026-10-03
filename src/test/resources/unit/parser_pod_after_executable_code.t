use strict;
use warnings;
use Test::More;

is(1, 1, 'code before POD runs');

=encoding utf8
=cut
=head1 ATTRIBUTES

This is POD after executable code.

=cut

is(2, 2, 'code after adjacent POD directives runs');

done_testing;
