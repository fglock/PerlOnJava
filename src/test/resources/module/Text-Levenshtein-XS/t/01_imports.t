#!perl -T
#
# This file is part of Text-Levenshtein-XS
#
# This software is copyright (c) 2016 by Nick Logan.
#
# This is free software; you can redistribute it and/or modify it under
# the same terms as the Perl 5 programming language system itself.
#
use 5.008;
use strict;
use warnings FATAL => 'all';
use Test::More tests => 1;

BEGIN { use_ok 'Text::Levenshtein::XS', qw/distance/ }



1;



__END__