package CatalystNetty;

use strict;
use warnings;
use Moose;
use namespace::autoclean;
use Catalyst;

extends 'Catalyst';

our $VERSION = '0.01';

__PACKAGE__->config(
    name                             => 'CatalystNetty',
    encoding                         => 'UTF-8',
    disable_component_regex_fallback => 1,
);

__PACKAGE__->setup;

1;
