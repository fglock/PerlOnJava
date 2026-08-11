################################################################################
#
# Copyright (c) Marcus Holland-Moritz. All rights reserved.
# This program is free software; you can redistribute it and/or modify
# it under the same terms as Perl itself.
#
# PerlOnJava bundles a Java implementation of the distribution's XS backend.
#
################################################################################

package Tie::Hash::Indexed;
use 5.004;
use strict;
use DynaLoader;
use Tie::Hash;
use vars qw($VERSION @ISA);

@ISA = qw(DynaLoader Tie::Hash);
$VERSION = '0.08';

bootstrap Tie::Hash::Indexed $VERSION;

1;
