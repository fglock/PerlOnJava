use Test2::V0;
use ExtUtils::testlib;
use URI::Fast qw(uri uri_split encode decode);

subtest 'XSUB(undef)' => sub{
  subtest 'no warnings' => sub{
    ok no_warnings{ encode undef }, 'encode';
    ok no_warnings{ decode undef }, 'decode';
    ok no_warnings{ uri_split undef }, 'uri_split';
    ok no_warnings{ uri undef }, 'uri';
    ok no_warnings{ uri->scheme(undef) }, 'set_scheme';
    ok no_warnings{ uri->frag(undef) }, 'set_frag';
    ok no_warnings{ uri->usr(undef) }, 'set_usr';
    ok no_warnings{ uri->pwd(undef) }, 'set_pwd';
    ok no_warnings{ uri->host(undef) }, 'set_host';
    ok no_warnings{ uri->port(undef) }, 'set_port';
    ok no_warnings{ uri->set_auth(undef) }, 'set_auth';
    ok no_warnings{ uri->set_path(undef) }, 'set_path';
    ok no_warnings{ uri->set_path_array(undef) }, 'set_path_array';
    ok no_warnings{ uri->set_query(undef) }, 'set_query';
    ok no_warnings{ uri->set_param('foo', [], undef) }, 'set_param(s,[],u)';
    ok no_warnings{ uri->set_param('foo', ['foo'], undef) }, 'set_param(s,[s],u)';
    ok no_warnings{ uri->query_keyset({}, undef) }, 'update_query_keyset';
  };

  subtest 'croaks' => sub{
    ok dies{ uri->get_param(undef) }, 'get_param';
    ok dies{ uri->set_param(undef, undef, undef) }, 'set_param(u,u,u)';
    ok dies{ uri->set_param('foo', undef, undef) }, 'set_param(s,u,u)';
    ok dies{ uri->update_query_keyset(undef, undef) }, 'update_query_keyset';
  };
};

done_testing;
