use strict;
use warnings;
use Test::More;

my $error = eval q{sub example { my $value = 2: }};
ok !defined $error, 'malformed source does not compile';
like $@, qr/syntax error.*near "2:"/, 'syntax diagnostic retains operand before colon';

done_testing;
