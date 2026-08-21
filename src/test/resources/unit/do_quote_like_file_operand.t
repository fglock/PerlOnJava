use strict;
use warnings;
use Test::More;

eval qq{ do qq(a file that does not exist); };
is($@, '', 'qq expression remains a valid do-file operand');

done_testing;
