use strict;
use warnings;
use Test::More;
use ExtUtils::MakeMaker;

plan skip_all => 'bundled MakeMaker is not loaded' unless $ExtUtils::MakeMaker::VERSION eq '7.78';

my $function_path = ExtUtils::MakeMaker::_installed_file_for_module('File::Spec');
ok(defined $function_path && -r $function_path,
   'function form finds an installed module');
is(MM->_installed_file_for_module('File::Spec'), $function_path,
   'method form ignores its class invocant');
done_testing();
