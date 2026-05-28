use strict;
use warnings;
use Test::More tests => 7;
use Config;
use File::Spec;

ok(File::Spec->file_name_is_absolute($Config{archlibexp}), 'archlibexp is absolute');
ok(File::Spec->file_name_is_absolute($Config{privlibexp}), 'privlibexp is absolute');

my $core_dir = File::Spec->catdir($Config{archlibexp}, 'CORE');
ok(-d $Config{privlibexp}, 'privlibexp directory exists');
ok(-d $Config{archlibexp}, 'archlibexp directory exists');
ok(-d $core_dir, 'archlib CORE directory exists');

is($Config{ldflags}, '', 'ldflags has an empty default');
is($Config{lddlflags}, '', 'lddlflags has an empty default');
