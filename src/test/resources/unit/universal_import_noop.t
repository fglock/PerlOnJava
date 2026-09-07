use strict;
use warnings;
use Test::More;

{
    package Issue1118::InheritedImport;
    our @ISA = qw(UNIVERSAL);
}

BEGIN {
    Issue1118::InheritedImport->import('already_available');
}

pass('an inherited default UNIVERSAL::import accepts an import list as a no-op');

done_testing;
