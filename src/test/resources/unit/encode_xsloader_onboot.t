use strict;
use warnings;
use Test::More;

use Encode ();

{
    package Encode;
    onBOOT;
}

ok(defined &Encode::onBOOT,
    'Java Encode registers the XSLoader onBOOT callback before strict compilation');

done_testing;
