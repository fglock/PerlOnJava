use strict;
use warnings;
use Test::More tests => 2;
use re 'eval';

{
    package DynamicTieCapture::Overloaded;
    use overload '""' => sub { 'abc' };
}

{
    package DynamicTieCapture::Scalar;
    sub TIESCALAR { bless [], __PACKAGE__ }
    sub STORE { }
    sub FETCH { "$1" }
}

package main;

tie my $dynamic, 'DynamicTieCapture::Scalar';
$dynamic = bless [], 'DynamicTieCapture::Overloaded';

my $matched = 'aab' =~ /(a)((??{ 'b' =~ m|(.)|; $dynamic }))/;
ok($matched, 'dynamic tied pattern matches');
is("[$1 $2]", '[a b]', 'dynamic tied FETCH sees nested capture');
