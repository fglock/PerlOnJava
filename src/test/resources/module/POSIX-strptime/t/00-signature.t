#!/usr/bin/perl

use strict;
print "1..1\n";

if ( !$ENV{TEST_SIGNATURE} ) {
    print "ok 1 # skip Set the environment variable",
      " TEST_SIGNATURE to enable this test\n";
}
elsif ( !-s 'SIGNATURE' ) {
    print "ok 1 # skip No signature file found\n";
}
elsif ( !eval { require Module::Signature; 1 } ) {
    print "ok 1 # skip ",
      "Next time around, consider install Module::Signature, ",
      "so you can verify the integrity of this distribution.\n";
}
elsif ( !eval { require Socket; Socket::inet_aton('pool.sks-keyservers.net') } )
{
    print "ok 1 # skip ", "Cannot connect to the keyserver\n";
}
else {
    ( Module::Signature::verify() == Module::Signature::SIGNATURE_OK() )
      or print "not ";
    print "ok 1 # Valid signature\n";
}

__END__
