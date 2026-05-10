#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Socket qw(EAI_FAIL EAI_NONAME);
use POSIX qw(ECONNRESET EWOULDBLOCK nice);

ok defined(EAI_FAIL), 'Socket exports EAI_FAIL';
ok defined(EAI_NONAME), 'Socket still exports EAI_NONAME';
ok EAI_FAIL != EAI_NONAME, 'EAI_FAIL is distinct from EAI_NONAME';

ok defined(ECONNRESET), 'POSIX exports ECONNRESET';
ok defined(EWOULDBLOCK), 'POSIX exports EWOULDBLOCK';
ok EWOULDBLOCK == POSIX::EWOULDBLOCK(), 'POSIX::EWOULDBLOCK is callable fully qualified';
ok nice(0), 'POSIX exports nice';

done_testing;
