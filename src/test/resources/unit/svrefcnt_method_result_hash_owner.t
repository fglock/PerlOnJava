use strict;
use warnings;
use Test::More tests => 1;

{
    package SvRefcntFieldValue;
    sub new { bless {}, shift }
}

{
    package SvRefcntFieldOwner;
    sub new { bless { value => SvRefcntFieldValue->new }, shift }
    sub value { return $_[0]->{value} }
}

my $owner = SvRefcntFieldOwner->new;
is(&Internals::SvREFCNT($owner->value) + 1, 2,
    'method-result count includes the hash-held field value');
