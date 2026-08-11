use strict;
use warnings;
use Test::More;

require ExtUtils::MakeMaker;

local $ENV{PERL_MM_USE_DEFAULT} = 1;
is(ExtUtils::MakeMaker::prompt('Use optional integration?', 'no'), 'no',
   'MakeMaker prompt selects its default in unattended mode');

done_testing;
